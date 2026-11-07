#!/usr/bin/env bash
#
# One-time setup of a fresh Ubuntu 24.04 VM (tested on Oracle Cloud's Always Free Ampere A1,
# 2 OCPU / 12 GB, ARM64). Run as the default `ubuntu` user:
#
#   curl -fsSL https://raw.githubusercontent.com/mofchris/vertex-social-graph-platform/main/deploy/bootstrap-vm.sh | bash
#
# It installs Docker from Docker's own apt repository, adds swap (the first image build is the
# memory peak), clones the repo to /opt/vertex and installs the systemd units that start the stack
# at boot and reset the demo nightly. It does not start the stack: fill in deploy/.env first, then
#   sudo systemctl start vertex
set -euo pipefail

REPO=${REPO:-https://github.com/mofchris/vertex-social-graph-platform.git}
DIR=/opt/vertex

echo "==> Docker Engine + Compose plugin"
if ! command -v docker > /dev/null; then
  sudo install -m 0755 -d /etc/apt/keyrings
  sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
  sudo apt-get update -qq
  sudo apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin git
fi
sudo usermod -aG docker "$USER"
sudo systemctl enable --now docker

echo "==> 4 GB swap"
if ! swapon --show | grep -q /swapfile; then
  sudo fallocate -l 4G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile > /dev/null
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab > /dev/null
fi

echo "==> Repository at $DIR"
if [ ! -d "$DIR/.git" ]; then
  sudo mkdir -p "$DIR"
  sudo chown "$USER:$USER" "$DIR"
  git clone "$REPO" "$DIR"
fi
if [ ! -f "$DIR/deploy/.env" ]; then
  cp "$DIR/deploy/.env.example" "$DIR/deploy/.env"
  chmod 600 "$DIR/deploy/.env"
fi
chmod +x "$DIR"/deploy/*.sh

echo "==> systemd units (start on boot, nightly reset)"
sudo cp "$DIR"/deploy/systemd/vertex.service "$DIR"/deploy/systemd/vertex-reset.service \
        "$DIR"/deploy/systemd/vertex-reset.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable vertex.service vertex-reset.timer

echo
echo "Done. Next: edit $DIR/deploy/.env (see the comments in it), then: sudo systemctl start vertex"
