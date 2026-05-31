#!/bin/bash

shopt -s expand_aliases
source ~/.bashrc

WORKING_DIRECTORY=$(dirname $(readlink -f $0))

export DOCKER_CONFIG=${WORKING_DIRECTORY}/.docker

export LANG=C

docker24 buildx use multiarch-builder

docker24 buildx build \
  -f Dockerfile-llm-wiki \
  --platform=linux/amd64 \
  --build-arg ARCH=amd64 \
  --build-arg http_proxy=http://134.80.223.17:7890 \
  --build-arg https_proxy=http://134.80.223.17:7890 \
  --build-arg no_proxy="localhost,127.0.0.1" \
  -t llm-wiki:v1.1.0-rc8 \
  --load \
  .

#docker24 buildx build \
#  -f Dockerfile-llm-wiki \
#  --platform=linux/arm64 \
#  --build-arg ARCH=arm64 \
#  --build-arg http_proxy=http://134.80.223.17:7890 \
#  --build-arg https_proxy=http://134.80.223.17:7890 \
#  --build-arg no_proxy="localhost,127.0.0.1" \
#  -t llm-wiki:v1.1.0-rc8 \
#  --load \
#  .
