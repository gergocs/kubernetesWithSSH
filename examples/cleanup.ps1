# Cleanup Kubernetes with SSH Demo Resources
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$K8sDir = Join-Path $ScriptDir "k8s"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " Cleaning up Kubernetes With SSH Demo Resources" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

Write-Host "Deleting demo namespaces and all contained resources..." -ForegroundColor Yellow
kubectl delete -f (Join-Path $K8sDir "00-namespaces.yaml") --ignore-not-found=true

Write-Host "Cleanup completed successfully." -ForegroundColor Green
