# Galahad Shared Library

Jenkins Shared Library powering Galahad — the homelab AI assistant.

## Structure

```
vars/
  notify.groovy    — Slack notifications, dashboard push
  llm.groovy       — LLM routing: Ollama → LM Studio → Codex
  homelab.groovy   — SSH, Docker/Portainer, Proxmox VM control
  research.groovy  — SearXNG search, web scrape, deep research
  codex.groovy     — Codex agent task queue and guidance

src/org/galahad/
  Config.groovy    — Shared constants: endpoints, credential IDs, channels
```

## Quick Reference

```groovy
// Notify
notify.slack('Message')
notify.ops('Alert: container down', 'danger')
notify.galahad('All systems green', 1.5)   // Slack + dashboard

// LLM
llm.call('Classify this', 'fast')          // Ollama
llm.call('Write a report on...', 'medium') // LM Studio
llm.call('Refactor this project', 'heavy') // Codex queue

// Homelab
homelab.ssh('10.201.52.171', 'docker ps')
homelab.container('jellyfin', 'restart')
homelab.vm(103, 'start')
def h = homelab.health()                   // {total, running, down, down_names}

// Research
def results = research.search('query', 5)
def report  = research.deep('query')       // Full pipeline: search → fetch → summarize → synthesize
research.saveToLogseq(report)

// Codex
codex.task('Build feature X')
codex.guidance('append', 'Focus on Y')
codex.abort('No longer needed')
def s = codex.status()
```

## Jenkins Credential IDs Required

| ID | Type | Description |
|----|------|-------------|
| `homelab-ssh-key` | SSH Key | root ed25519 — all homelab nodes |
| `slack-bot-token` | Secret Text | Galahad Slack bot OAuth token |
| `slack-webhook-galahad` | Secret Text | #galahad incoming webhook |
| `portainer-api-key` | Secret Text | Portainer API key for VM105 |
| `proxmox-api-token` | Secret Text | Full token string: `root@pam!homelab_gateway=...` |
