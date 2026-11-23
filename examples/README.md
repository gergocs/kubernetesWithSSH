# Kubernetes with SSH - Example & Testing Suite

This directory contains a complete, production-style, multi-tier Kubernetes example application designed specifically to test all features of the **Kubernetes with SSH** IntelliJ plugin.

---

## 📋 Architecture & Workloads

The demo application contains varied workloads across multiple namespaces to exercise every UI component and command of the plugin:

| Workload | Namespace | Kind | Replicas | Key Test Purpose |
|---|---|---|---|---|
| `frontend-deployment` | `demo-app` | Deployment | 3 | Nginx web server, ConfigMap mount, HTTP readiness/liveness probes, scaling, rollout restart, and image updates. |
| `backend-api` | `demo-app` | Deployment | 2 | Continuous background logging loop, secret injection, live logs streaming. |
| `redis-cache` | `demo-app` | Deployment | 1 | Redis service, single-replica stateful container inspection. |
| `multi-container-demo` | `demo-app` | Pod | 1 (2 containers) | Multi-container pod (`web-app` + `log-agent` sidecar with shared volume) for container switching in Pod Terminal and logs. |
| `crashloop-demo-pod` | `demo-app` | Pod | 1 | Simulates runtime exit error to test `CrashLoopBackOff` status coloring, `Describe Pod`, and Force Delete. |
| `batch-reporter-job` | `demo-app` | CronJob | Scheduled | Periodic batch execution to test `Completed` / `Succeeded` pod status. |
| `cluster-metrics-agent` | `demo-monitoring` | Deployment | 1 | Background metric logger in an isolated namespace to verify namespace switching. |

---

## 🚀 Quick Deployment

### Option A: Direct Deployment to Existing Cluster (Minikube, Kind, k3s, MicroK8s, Cloud, Remote Node)

Ensure your `kubectl` is pointed to your target cluster, then run:

#### Linux / macOS / WSL:
```bash
chmod +x examples/deploy.sh
./examples/deploy.sh
```

#### Windows (PowerShell):
```powershell
.\examples\deploy.ps1
```

#### Or using manual `kubectl` commands:
```bash
kubectl apply -f examples/k8s/00-namespaces.yaml
kubectl apply -f examples/k8s/
```

---

### Option B: Local SSH Sandbox (Docker Compose)

If you do not have a dedicated remote Linux SSH server and want to test the plugin's SSH connection locally on your workstation, use the included SSH sandbox container:

```bash
cd examples/docker-ssh-sandbox
docker compose up -d --build
```

This launches an OpenSSH server on `localhost:2222` with `kubectl` and `docker` CLI connected to your local cluster and Docker daemon:

- **Host**: `127.0.0.1` (or `localhost`)
- **Port**: `2222`
- **Username**: `k8suser`
- **Password**: `k8spassword`
- **Kubectl Path**: `kubectl` (or `/usr/local/bin/kubectl`)
- **Docker Path**: `docker` (or `/usr/bin/docker`)

---

## 🧪 Plugin Feature Verification Matrix

Use the deployed workloads to verify each plugin capability:

### 1. SSH Connection Setup & Testing
1. Navigate to **Settings / Preferences -> Tools -> Kubernetes over SSH**.
2. Create or edit an SSH profile:
   - Host, Port, Username, and Auth (Password or Private Key).
   - Set **Default Namespace** to `demo-app`.
3. Click **Test SSH Connection** — verify green success status and client version output.

### 2. Namespace Switching & Pod Discovery
1. Open the **Kubernetes SSH** tool window on the right side of the IDE.
2. Verify the **Namespace** dropdown lists `demo-app`, `demo-monitoring`, `default`, `kube-system`, etc.
3. Switch to `demo-app` -> verify all frontend, backend, redis, multi-container, and crashloop pods load with correct row counts, IP addresses, node names, and restart counts.
4. Switch to `demo-monitoring` -> verify `cluster-metrics-agent` pod appears.

### 3. Pod Status Indicators & Inspection
- **Green (Running)**: `frontend-deployment-*`, `backend-api-*`, `redis-cache-*`.
- **Red / Orange (CrashLoopBackOff / Error)**: `crashloop-demo-pod`.
- **Multi-container count**: Verify `multi-container-demo` shows `2/2` Ready.
- **Pod Details / Describe**: Double-click or right-click any pod -> select **Describe Pod** or **View Details** to inspect annotations, events, and container specs.

### 4. Interactive Pod Terminal
1. Right-click `frontend-deployment-*` or `multi-container-demo` -> select **Execute Pod Terminal**.
2. On multi-container pods, verify the container selector dropdown allows switching between `web-app` and `log-agent`.
3. Run shell commands (`ls -la`, `ps aux`, `cat /etc/os-release`, `env`).
4. Test quick command buttons and keyboard navigation.

### 5. Live Pod Logs Viewer
1. Right-click `backend-api-*` -> select **View Pod Logs**.
2. Verify continuous real-time log streaming (`[INFO] Processing request batch...`).
3. Test search filter, copy to clipboard, and log line wrapping.

### 6. Deployment Editor & Management
1. Select a `frontend-deployment-*` pod and click **Edit Deployment** (or right-click -> **Edit Deployment**).
2. **Replicas Scaling**: Change replicas from `3` to `5` and click **Scale** -> verify 5 pods appear in the table.
3. **Rollout Restart**: Click **Rollout Restart** -> verify old pods terminate and new pods start.
4. **Update Image**: Change container image to `nginx:1.27.4-alpine` -> verify rollout status and image column update.
5. **Dry-Run Validation**: Modify manifest YAML and test **Dry Run (Server)** before applying.

### 7. Docker Images Explorer & Jump
1. Switch to the **Docker Images** tab.
2. Verify images (`nginx`, `busybox`, `redis`, `alpine`) are listed with ID, Tag, Created date, and Size.
3. Switch back to **Pods**, right-click any pod -> select **Show in Docker Images** -> verify the Docker Images tab opens with the search bar pre-populated.

### 8. Custom Kubectl Console
1. Switch to the **Kubectl** tab.
2. Execute custom commands:
   - `get nodes`
   - `top nodes` (if metrics-server is active)
   - `get services -n demo-app`
   - `get configmap,secret -n demo-app`
3. Test arrow up/down command history, preset dropdown snippets, and clear output button.

### 9. Pod Deletion
1. Right-click any replica pod -> select **Delete Pod**. Confirm the deletion dialog.
2. Verify Kubernetes automatically replaces the pod with a fresh replica.
3. Test **Force Delete** on the `crashloop-demo-pod`.

---

## 🧹 Teardown & Cleanup

To remove all demo resources from your cluster:

#### Linux / macOS / WSL:
```bash
./examples/cleanup.sh
```

#### Windows (PowerShell):
```powershell
.\examples\cleanup.ps1
```

#### Manual:
```bash
kubectl delete namespace demo-app demo-monitoring
```
