import argparse
import asyncio
import base64
import json
import logging
import shutil
import wave
from pathlib import Path
from tempfile import NamedTemporaryFile

from fastapi import FastAPI, Request, WebSocket
from fastapi.responses import JSONResponse
from huggingface_hub import snapshot_download
from modelscope.hub.snapshot_download import snapshot_download as modelscope_download
import uvicorn


logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("local-asr")
app = FastAPI()
SERVICE_VERSION = "local-asr-service-v3"
state = {
    "engine": None,
    "model_id": None,
    "model_path": None,
    "device": "cpu",
    "compute_type": "int8",
    "model": None,
}


@app.get("/health")
async def health():
    return {
        "ok": True,
        "version": SERVICE_VERSION,
        "loaded": state["model"] is not None,
        "engine": state["engine"],
        "model_id": state["model_id"],
    }


@app.exception_handler(Exception)
async def unhandled_exception_handler(_request: Request, exc: Exception):
    logger.exception("unhandled request failed")
    return JSONResponse({"status": "error", "message": str(exc)}, status_code=500)


@app.post("/models/ensure")
async def ensure_model(request: Request):
    try:
        payload = await read_json_payload(request)
        engine = payload["engine"]
        model_id = payload["model_id"]
        models_dir = Path(payload["models_dir"])
        target = model_path(models_dir, engine, model_id)
        logger.info("ensure model start engine=%s model_id=%s target=%s", engine, model_id, target)
        cleanup_incomplete_cache(target)
        if cache_ready(target, engine):
            logger.info("model cache hit target=%s", target)
            return {"status": "ready", "path": str(target)}
        target.parent.mkdir(parents=True, exist_ok=True)
        source = payload.get("download_source", "modelscope")
        logger.info("model download start source=%s model_id=%s target=%s", source, model_id, target)
        if source == "modelscope":
            try:
                modelscope_download(model_id, cache_dir=str(target.parent), local_dir=str(target))
            except Exception as exc:
                logger.warning("modelscope download failed, fallback to huggingface: %s", exc)
                snapshot_download(model_id, local_dir=str(target), local_dir_use_symlinks=False)
        else:
            snapshot_download(model_id, local_dir=str(target), local_dir_use_symlinks=False)
        if not cache_ready(target, engine):
            raise RuntimeError(f"Model cache is incomplete after download: {target}")
        logger.info("model download ready target=%s", target)
        return {"status": "ready", "path": str(target)}
    except Exception as exc:
        logger.exception("ensure model failed")
        return JSONResponse({"status": "error", "message": str(exc)}, status_code=500)


@app.post("/models/load")
async def load_model(request: Request):
    try:
        payload = await read_json_payload(request)
        engine = payload["engine"]
        model_path_value = payload["model_path"]
        logger.info(
            "load model start engine=%s model_path=%s device=%s compute_type=%s",
            engine,
            model_path_value,
            payload.get("device", "cpu"),
            payload.get("compute_type", "int8"),
        )
        if not cache_ready(Path(model_path_value), engine):
            raise RuntimeError(f"Model cache is incomplete: {model_path_value}")
        model = load_engine(engine, model_path_value, payload.get("device", "cpu"), payload.get("compute_type", "int8"))
    except Exception as exc:
        logger.exception("load model failed")
        return JSONResponse({"status": "error", "message": str(exc)}, status_code=500)
    state.update({
        "engine": engine,
        "model_id": payload["model_id"],
        "model_path": model_path_value,
        "device": payload.get("device", "cpu"),
        "compute_type": payload.get("compute_type", "int8"),
        "model": model,
    })
    logger.info("load model ready engine=%s model_id=%s", engine, payload["model_id"])
    return {"status": "ready"}


@app.websocket("/ws/asr")
async def asr_ws(websocket: WebSocket):
    await websocket.accept()
    audio = bytearray()
    segment_id = "local"
    sample_rate = 16000
    channels = 1
    try:
        while True:
            message = await websocket.receive_text()
            payload = json.loads(message)
            if payload.get("type") == "audio":
                segment_id = payload.get("segment_id", segment_id)
                sample_rate = int(payload.get("sample_rate", sample_rate))
                channels = int(payload.get("channels", channels))
                audio.extend(base64.b64decode(payload["audio"]))
            elif payload.get("type") == "end":
                text = await asyncio.to_thread(transcribe_audio, bytes(audio), sample_rate, channels)
                await websocket.send_json({"type": "final", "segment_id": segment_id, "text": text})
                await websocket.close()
                return
    except Exception as exc:
        await websocket.send_json({"type": "error", "segment_id": segment_id, "message": str(exc)})
        await websocket.close()


def model_path(models_dir: Path, engine: str, model_id: str) -> Path:
    return models_dir / "asr" / engine / model_id.replace("/", "_").replace("\\", "_")


def cache_ready(path: Path, engine: str) -> bool:
    if not path.is_dir():
        return False
    if any(part.name.startswith("._____temp") for part in path.rglob("*")):
        return False
    if engine == "faster-whisper":
        return (path / "model.bin").is_file()
    return any(item.is_file() for item in path.rglob("*") if not item.name.startswith("."))


def cleanup_incomplete_cache(path: Path) -> None:
    if not path.exists():
        return
    for temp_dir in path.rglob("._____temp"):
        logger.info("remove incomplete model temp dir=%s", temp_dir)
        shutil.rmtree(temp_dir, ignore_errors=True)


async def read_json_payload(request: Request) -> dict:
    body = await request.body()
    if not body:
        raise RuntimeError("Request body is empty. Check that Java sent a JSON body.")
    try:
        return json.loads(body.decode("utf-8"))
    except Exception as exc:
        raise RuntimeError(f"Request body is not valid JSON: {exc}") from exc


def load_engine(engine: str, model_path_value: str, device: str, compute_type: str):
    if engine == "faster-whisper":
        from faster_whisper import WhisperModel
        return WhisperModel(model_path_value, device=device, compute_type=compute_type)
    if engine == "anime-whisper":
        return AnimeWhisperModel(model_path_value, device)
    if engine in ("sensevoice", "funasr-nano"):
        try:
            from funasr import AutoModel
        except Exception as exc:
            raise RuntimeError("Install funasr in python/requirements.txt to use this engine") from exc
        return AutoModel(model=model_path_value, device=device)
    raise RuntimeError(f"Unsupported ASR engine: {engine}")


def transcribe_audio(pcm16le: bytes, sample_rate: int, channels: int) -> str:
    if state["model"] is None:
        raise RuntimeError("ASR model is not loaded")
    audio = pcm16_to_float_mono(pcm16le, channels)
    if is_low_energy(audio):
        logger.info("skip low-energy audio bytes=%s", len(pcm16le))
        return ""
    if state["engine"] == "faster-whisper":
        segments, _info = state["model"].transcribe(audio, vad_filter=False)
        return "".join(segment.text for segment in segments).strip()
    if state["engine"] == "anime-whisper":
        return state["model"].transcribe(audio, sample_rate)
    with NamedTemporaryFile(suffix=".wav", delete=False) as temp:
        wav_path = Path(temp.name)
    try:
        with wave.open(str(wav_path), "wb") as wav:
            wav.setnchannels(channels)
            wav.setsampwidth(2)
            wav.setframerate(sample_rate)
            wav.writeframes(pcm16le)
        result = state["model"].generate(input=str(wav_path))
        if isinstance(result, list) and result:
            return str(result[0].get("text", "")).strip()
        return str(result).strip()
    finally:
        wav_path.unlink(missing_ok=True)


def pcm16_to_float_mono(pcm16le: bytes, channels: int):
    import numpy as np
    audio = np.frombuffer(pcm16le, dtype=np.int16).astype(np.float32) / 32768.0
    if channels > 1:
        audio = audio.reshape(-1, channels).mean(axis=1)
    return audio


def is_low_energy(audio) -> bool:
    import numpy as np
    if audio.size == 0:
        return True
    rms = float(np.sqrt(np.mean(np.square(audio))))
    peak = float(np.max(np.abs(audio)))
    return rms < 0.003 and peak < 0.02


class AnimeWhisperModel:
    def __init__(self, model_path_value: str, device: str):
        try:
            import numpy as np
            import torch
            from transformers import AutoProcessor, WhisperForConditionalGeneration
        except Exception as exc:
            raise RuntimeError("Install python/requirements-anime.txt to use anime-whisper") from exc
        self.np = np
        self.torch = torch
        self.device = "cuda" if device == "cuda" and torch.cuda.is_available() else "cpu"
        dtype = torch.float16 if self.device == "cuda" else torch.float32
        self.processor = AutoProcessor.from_pretrained(model_path_value, local_files_only=True)
        self.model = WhisperForConditionalGeneration.from_pretrained(
            model_path_value,
            local_files_only=True,
            torch_dtype=dtype,
        ).to(self.device)
        self.model.eval()

    def transcribe(self, audio, sample_rate: int) -> str:
        features = self.processor(audio, sampling_rate=sample_rate, return_tensors="pt").input_features.to(self.device)
        forced_decoder_ids = self.processor.get_decoder_prompt_ids(language="Japanese", task="transcribe")
        with self.torch.inference_mode():
            ids = self.model.generate(
                features,
                forced_decoder_ids=forced_decoder_ids,
                do_sample=False,
                num_beams=1,
                no_repeat_ngram_size=5,
                repetition_penalty=1.0,
            )
        return self.processor.batch_decode(ids, skip_special_tokens=True)[0].strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    uvicorn.run(app, host="127.0.0.1", port=args.port, log_level="info")


if __name__ == "__main__":
    main()
