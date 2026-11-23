# Deploy Kubernetes with SSH Demo Example Application
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$K8sDir = Join-Path $ScriptDir "k8s"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " Deploying Kubernetes With SSH Demo Example Application" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Check if kubectl is available
if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) {
    Write-Error "Error: 'kubectl' command was not found in PATH. Please ensure kubectl is installed and configured."
    exit 1
}

Write-Host "[1/7] Creating namespaces..." -ForegroundColor Yellow
kubectl apply -f (Join-Path $K8sDir "00-namespaces.yaml")

Write-Host "[2/7] Deploying Frontend..." -ForegroundColor Yellow
kubectl apply -f (Join-Path $K8sDir "01-frontend.yaml")

Write-Host "[3/7] Deploying Backend API..." -ForegroundColor Yellow
kubectl apply -f (Join-Path $K8sDir "02-backend.yaml")

Write-Host "[4/7] Deploying Redis Cache..." -ForegroundColor Yellow
kubectl apply -f (Join-Path $K8sDir "03-redis.yaml")

Write-Host "[5/7] Deploying Multi-Container Pod..." -ForegroundColor Yellow
kubectl apply -f (Join-Path $K8sDir "04-multi-container-pod.yaml")

Write-Host "[6/7] Deploying CrashLoop Test Pod..." -ForegroundColor Yellow
kubectl apply -f (Join-Path $K8sDir "05-crashloop-test.yaml")

Write-Host "[7/7] Deploying CronJob & Monitoring..." -ForegroundColor Yellow
kubectl apply -f (Join-Path $K8sDir "06-cronjob.yaml")
kubectl apply -f (Join-Path $K8sDir "07-monitoring.yaml")

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Green
Write-Host " Deployment applied successfully!" -ForegroundColor Green
Write-Host " Waiting for demo-app pods to become ready..." -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green

try {
    kubectl wait --namespace demo-app --for=condition=ready pod --selector=app=frontend --timeout=60s
} catch {
    Write-Warning "Timed out waiting for frontend pods or condition already matched."
}

Write-Host "`nCurrent Pods in 'demo-app':" -ForegroundColor Cyan
kubectl get pods -n demo-app -o wide

Write-Host "`nCurrent Pods in 'demo-monitoring':" -ForegroundColor Cyan
kubectl get pods -n demo-monitoring -o wide

Write-Host "`nReady to test in IntelliJ IDEA with the Kubernetes with SSH plugin!" -ForegroundColor Green
