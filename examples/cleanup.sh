#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="$SCRIPT_DIR/k8s"

echo "=========================================================="
echo " Cleaning up Kubernetes With SSH Demo Resources"
echo "=========================================================="

echo "Deleting demo namespaces and all contained resources..."
kubectl delete -f "$K8S_DIR/00-namespaces.yaml" --ignore-not-found=true

echo "Cleanup completed successfully."
