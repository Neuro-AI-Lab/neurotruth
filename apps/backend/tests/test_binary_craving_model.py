from __future__ import annotations

import importlib.util
import hashlib
from contextlib import nullcontext
from pathlib import Path

import numpy as np
import pytest

from app.ml.craving.model import CravingModel, EXPECTED_WEIGHTS_SHA256, InvalidSensorWindow


BACKEND = Path(__file__).resolve().parents[1]
MODEL_DIR = BACKEND / "model" / "weights" / "final_model_win20s_high2p4"
SHA256 = "2493e5d75fcdc9b066b341deb47f81c9db49aac37c626619b7afe1a6b2fc354c"


def test_copied_weights_match_runtime_checksum_constant() -> None:
    actual = hashlib.sha256((MODEL_DIR / "model_weights.pt").read_bytes()).hexdigest()
    assert actual == SHA256 == EXPECTED_WEIGHTS_SHA256


def sensor_payload(*, include_gsr: bool = True) -> dict:
    start = 1_700_000_000_000
    samples = [
        {"sensor": "PPG_GREEN", "timestampMs": start + round(index * 20_000 / 1024),
         "value": float(np.sin(index / 17.0) + index / 1024.0)}
        for index in range(1024)
    ]
    if include_gsr:
        samples.extend(
            {"sensor": "EDA", "timestampMs": start + index * 1000, "value": float(index)}
            for index in range(20)
        )
    return {"windowStartMs": start, "windowEndMs": start + 20_000, "samples": samples}


def test_preprocess_is_two_channels_1024_minmax_without_filter() -> None:
    model = CravingModel(MODEL_DIR / "model_weights.pt", requested_device="cpu")
    tensor, quality = model.preprocess(sensor_payload())
    assert tensor.shape == (1, 2, 1024)
    assert tensor.dtype == np.float32
    assert np.isclose(tensor[0, 0].min(), 0.0) and np.isclose(tensor[0, 0].max(), 1.0)
    assert np.isclose(tensor[0, 1].min(), 0.0) and np.isclose(tensor[0, 1].max(), 1.0)
    assert quality["ppgSensor"] == "PPG_GREEN"
    assert quality["gsrMissing"] is False
    assert model.registration_config["training_stride_sec"] == 1.0
    assert model.registration_config["operational_stride_sec"] == 10.0
    assert model.registration_config["filter"] == "none"


def test_missing_gsr_becomes_zero_channel_and_missing_ppg_is_rejected() -> None:
    model = CravingModel(MODEL_DIR / "model_weights.pt", requested_device="cpu")
    tensor, quality = model.preprocess(sensor_payload(include_gsr=False))
    assert np.count_nonzero(tensor[0, 1]) == 0
    assert quality["gsrMissing"] is True
    with pytest.raises(InvalidSensorWindow, match="PPG"):
        model.preprocess({"windowStartMs": 0, "windowEndMs": 20_000, "samples": []})


def test_constant_channels_become_zero_without_a_filter() -> None:
    model = CravingModel(MODEL_DIR / "model_weights.pt", requested_device="cpu")
    payload = sensor_payload()
    for sample in payload["samples"]:
        sample["value"] = 7.0
    tensor, _ = model.preprocess(payload)
    assert tensor.shape == (1, 2, 1024)
    assert np.count_nonzero(tensor) == 0


def test_service_logits_and_softmax_match_supplied_model_on_cpu() -> None:
    torch = pytest.importorskip("torch")
    model = CravingModel(
        MODEL_DIR / "model_weights.pt", MODEL_DIR / "model_metadata.json",
        expected_sha256=SHA256, requested_device="cpu",
    )
    model.load()
    payload = sensor_payload()
    input_array, _ = model.preprocess(payload)
    event = model.predict(payload)

    spec = importlib.util.spec_from_file_location("supplied_model", MODEL_DIR / "model.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    supplied = module.load_trained_model(MODEL_DIR / "model_weights.pt", device="cpu")
    with torch.inference_mode():
        expected = torch.softmax(supplied(torch.from_numpy(input_array)), dim=1).numpy()[0]
    actual = np.array([event["classProbabilities"]["low"], event["classProbabilities"]["high"]])
    assert np.allclose(actual, expected, atol=1e-5)
    assert event["predictionSchema"] == "binary-craving-v1"
    assert event["class"] in (0, 1)
    assert event["classCode"] in ("low", "high")
    assert event["cravingProbability"] == event["classProbabilities"]["high"]
    assert abs(sum(actual) - 1.0) <= 1e-5


def test_auto_device_falls_back_to_cpu_when_cuda_is_unavailable() -> None:
    smoke_shapes = []

    class Truth:
        def all(self): return self
        def item(self): return True

    class Output:
        shape = (1, 2)

    class Cuda:
        @staticmethod
        def is_available(): return False
        @staticmethod
        def empty_cache(): return None

    class Torch:
        cuda = Cuda()
        float32 = "float32"
        @staticmethod
        def inference_mode(): return nullcontext()
        @staticmethod
        def zeros(shape, **kwargs):
            smoke_shapes.append(shape)
            return object()
        @staticmethod
        def isfinite(value): return Truth()

    class Network:
        def load_state_dict(self, state): pass
        def to(self, device): return self
        def eval(self): return self
        def __call__(self, value): return Output()

    model = CravingModel(MODEL_DIR / "model_weights.pt", requested_device="auto")
    model.torch = Torch()
    loaded = model._load_for_requested_device(Network, {})
    assert isinstance(loaded, Network)
    assert model.actual_device == "cpu"
    assert model.fallback is True
    assert model.fallback_reason == "cuda_unavailable"
    assert smoke_shapes == [(1, 2, 1024)]
