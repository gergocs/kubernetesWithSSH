FROM alpine:3.20

# Install OpenSSH server, kubectl dependencies, bash, curl, sudo, docker CLI
RUN apk add --no-cache \
    openssh \
    bash \
    curl \
    sudo \
    docker-cli \
    shadow \
    coreutils

# Download and install kubectl
RUN curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl" && \
    chmod +x kubectl && \
    mv kubectl /usr/local/bin/

# Configure SSH server
RUN ssh-keygen -A && \
    mkdir -p /var/run/sshd && \
    sed -i 's/#PasswordAuthentication yes/PasswordAuthentication yes/' /etc/ssh/sshd_config && \
    sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin yes/' /etc/ssh/sshd_config

# Create demo user 'k8suser' with password 'k8spassword' and sudo privileges
RUN useradd -m -s /bin/bash k8suser && \
    echo "k8suser:k8spassword" | chpasswd && \
    echo "root:rootpassword" | chpasswd && \
    echo "k8suser ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

# Create kubeconfig directory
RUN mkdir -p /home/k8suser/.kube && \
    chown -R k8suser:k8suser /home/k8suser/.kube

EXPOSE 22

CMD ["/usr/sbin/sshd", "-D", "-e"]
