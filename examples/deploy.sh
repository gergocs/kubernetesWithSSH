#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="$SCRIPT_DIR/k8s"

echo "=========================================================="
echo " Deploying Kubernetes With SSH Demo Example Application"
echo "=========================================================="

# Check if kubectl is available
if ! command -v kubectl &> /dev/null; then
    echo "Error: 'kubectl' command was not found in PATH."
    echo "Please ensure kubectl is installed and configured."
    exit 1
fi

echo "[1/7] Creating namespaces..."
kubectl apply -f "$K8S_DIR/00-namespaces.yaml"

echo "[2/7] Deploying Frontend..."
kubectl apply -f "$K8S_DIR/01-frontend.yaml"

echo "[3/7] Deploying Backend API..."
kubectl apply -f "$K8S_DIR/02-backend.yaml"

echo "[4/7] Deploying Redis Cache..."
kubectl apply -f "$K8S_DIR/03-redis.yaml"

echo "[5/7] Deploying Multi-Container Pod..."
kubectl apply -f "$K8S_DIR/04-multi-container-pod.yaml"

echo "[6/7] Deploying CrashLoop Test Pod..."
kubectl apply -f "$K8S_DIR/05-crashloop-test.yaml"

echo "[7/7] Deploying CronJob & Monitoring..."
kubectl apply -f "$K8S_DIR/06-cronjob.yaml"
kubectl apply -f "$K8S_DIR/07-monitoring.yaml"

echo ""
echo "=========================================================="
echo " Deployment applied successfully!"
echo " Waiting for demo-app pods to become ready..."
echo "=========================================================="
kubectl wait --namespace demo-app --for=condition=ready pod --selector=app=frontend --timeout=60s || true

echo ""
echo "Current Pods in 'demo-app':"
kubectl get pods -n demo-app -o wide

echo ""
echo "Current Pods in 'demo-monitoring':"
kubectl get pods -n demo-monitoring -o wide

echo ""
echo "Ready to test in IntelliJ IDEA with the Kubernetes with SSH plugin!"
