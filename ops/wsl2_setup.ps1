# WSL2 + GPU + CUDA + Python venv bootstrap (PowerShell, run as Administrator)
# This script enables required Windows features, installs Ubuntu 22.04, updates WSL,
# and prepares the host-side prerequisites for CUDA on WSL.

Write-Host "Enabling Windows features for WSL2..."
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart | Out-Null
dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart | Out-Null

Write-Host "Updating WSL..."
wsl --update
wsl --set-default-version 2

# Optional: install Ubuntu 22.04 (skip if already installed)
$DistroName = "Ubuntu-22.04"
$installed = (wsl --list --verbose) -match $DistroName
if (-not $installed) {
  Write-Host "Installing $DistroName..."
  wsl --install -d $DistroName
  Write-Host "Please create a UNIX username/password in the new terminal if prompted."
}

Write-Host "`n[GPU Driver] Ensure you have the latest NVIDIA Game Ready/Studio driver with WSL support installed on Windows."
Write-Host "Open NVIDIA Control Panel → System Information to verify 'Driver Model: WDDM 3.x'"
Write-Host "If needed, download from NVIDIA.com; WSL requires a recent driver."
Write-Host "`nNext: In your Ubuntu shell, run:  ./ops/wsl_cuda_venv_setup.sh"
