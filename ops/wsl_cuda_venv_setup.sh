#!/usr/bin/env bash
set -euo pipefail

# CUDA & dev packages for Ubuntu on WSL2 (run inside Ubuntu shell).
# NOTE: WSL uses the Windows host NVIDIA driver; here we install CUDA toolkit/runtime.

sudo apt-get update
sudo apt-get install -y build-essential git wget curl unzip pkg-config \
    python3 python3-venv python3-dev python3-pip \
    openjdk-21-jdk-headless

# Install CUDA toolkit from Ubuntu repo (sufficient for most Python libs in WSL2)
sudo apt-get install -y nvidia-cuda-toolkit

# Verify GPU is visible
echo "nvidia-smi (from Windows host driver) should work inside WSL:"
nvidia-smi || true

# Create workspace
mkdir -p ~/ws
cd ~/ws

# Python venv
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip wheel setuptools

# PyTorch (CUDA 12.x build) — adjust if needed
pip install --index-url https://download.pytorch.org/whl/cu121 torch torchvision torchaudio

# Common LLM runtimes (choose what you use)
pip install vllm==0.5.4.post1  # server for AWQ/GPTQ/FP16 models
pip install llama-cpp-python==0.2.90  # GGUF-based, GPU offload via cuBLAS
pip install transformers==4.44.2 accelerate==0.34.2

# Java build convenience
cd -
echo "Done. Activate venv with: source ~/ws/.venv/bin/activate"
