#!/bin/bash

dnsmasq --no-daemon &

sleep 2

if [ ! -f /workspace/wiki.toml ]; then
   echo '[Info] Initialize knowledge base ...'
   olw init --help
   olw watch --help
   olw --version
   olw init \
      /vaults/knowledge-base \
      --provider ollama \
      --base-url http://ollama.local:11434 \
      --model llama3.2:3b \
      --fast-model llama3.2:3b \
      --no-interactive
   echo '[Info] Initialize complete ...'
fi

echo '[Info] Start knowledge base listener ...'
exec olw watch --vault /vaults/knowledge-base --interval 60
