#!/usr/bin/env bash
#
# One-time setup of the machine that runs the backend: a fresh Ubuntu 24.04 VM (for example
# Oracle Cloud's Always Free Ampere A1), or Ubuntu under WSL 2 on a Windows PC (needs systemd=true
# in /etc/wsl.conf). On a VM, as the default `ubuntu` user:
#
#   curl -fsSL https://raw.githubusercontent.com/mofchris/vertex-social-graph-platform/main/deploy/bootstrap-vm.sh | bash
#
# Under WSL, as root for the WSL user (see deploy/README.md, "Running it on a Windows PC"):
#
#   wsl -u root -- env RUN_AS=<wsl user> REPO=<repo path or URL> BRANCH=<branch> bash deploy/bootstrap-vm.sh
#
# It installs Docker from Docker's own apt repository, adds swap (the first image build is the
# memory peak; skipped under WSL, which has its own swap), clones the repo to /opt/vertex and
# installs the systemd units that start the stack at boot and reset the demo nightly. It does
# not start the stack: fill in deploy/.env first, then
#   sudo systemctl start vertex
set -euo pipefail

REPO=${REPO:-https://github.com/mofchris/vertex-social-graph-platform.git}
BRANCH=${BRANCH:-main}
RUN_AS=${RUN_AS:-$USER}   # the account the stack runs as (owns /opt/vertex, in the docker group)
DIR=/opt/vertex
WSL=$(grep -qi microsoft /proc/version && echo 1 || true)

echo "==> Docker Engine + Compose plugin"
if ! command -v docker > /dev/null; then
  sudo install -m 0755 -d /etc/apt/keyrings
  sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
  sudo apt-get update -qq
  sudo apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin git
fi
sudo usermod -aG docker "$RUN_AS"
sudo systemctl enable --now docker

echo "==> 4 GB swap"
if [ -z "$WSL" ] && ! swapon --show | grep -q /swapfile; then
  sudo fallocate -l 4G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile > /dev/null
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab > /dev/null
fi

echo "==> Repository at $DIR"
if [ ! -d "$DIR/.git" ]; then
  sudo mkdir -p "$DIR"
  sudo chown "$RUN_AS:$RUN_AS" "$DIR"
  sudo -u "$RUN_AS" git clone --branch "$BRANCH" "$REPO" "$DIR"
fi
if [ ! -f "$DIR/deploy/.env" ]; then
  sudo -u "$RUN_AS" install -m 600 "$DIR/deploy/.env.example" "$DIR/deploy/.env"
fi
chmod +x "$DIR"/deploy/*.sh

echo "==> systemd units (start on boot, nightly reset)"
for unit in vertex.service vertex-reset.service vertex-reset.timer; do
  sed "s/^User=ubuntu\$/User=$RUN_AS/" "$DIR/deploy/systemd/$unit" | sudo tee "/etc/systemd/system/$unit" > /dev/null
done
sudo systemctl daemon-reload
sudo systemctl enable vertex.service vertex-reset.timer

echo
echo "Done. Next: edit $DIR/deploy/.env (see the comments in it), then: sudo systemctl start vertex"
