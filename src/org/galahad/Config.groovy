package org.galahad

class Config implements Serializable {
    // LLM endpoints
    static final String OLLAMA_URL      = 'http://100.111.121.128:11434'
    static final String LMSTUDIO_URL    = 'http://100.71.100.103:1234'
    static final String LMSTUDIO_MODEL  = 'mistralai/ministral-3-14b-reasoning'
    static final String OLLAMA_MODEL    = 'qwen3.5:9b'
    static final String CODEX_MODEL     = 'gpt-5.4'

    // Homelab hosts
    static final String PROXMOX_HOST    = '10.201.52.200'
    static final String VM105_HOST      = '10.201.52.171'
    static final String TRUENAS_HOST    = '10.201.52.170'
    static final String JELLYFIN_HOST   = '10.201.52.207'

    // Services on VM105
    static final String STT_PUSH_URL    = 'http://10.201.52.171:8200/push'
    static final String PORTAINER_URL   = 'http://10.201.52.171:9000/api'
    static final int    PORTAINER_EP    = 3
    static final String N8N_URL         = 'http://10.201.52.171:5678'
    static final String SEARXNG_URL     = 'http://10.201.52.171:8888'
    static final String VALKEY_BRIDGE   = 'http://10.201.52.200:7379'

    // Proxmox API
    static final String PROXMOX_API     = 'https://10.201.52.200:8006/api2/json'
    static final String PROXMOX_NODE    = 'proxmox'

    // Jenkins credential IDs
    static final String CRED_SSH        = 'homelab-ssh-key'
    static final String CRED_SLACK_TOKEN = 'slack-bot-token'
    static final String CRED_SLACK_HOOK  = 'slack-webhook-galahad'
    static final String CRED_PORTAINER  = 'portainer-api-key'
    static final String CRED_PROXMOX    = 'proxmox-api-token'

    // Slack channels
    static final String SLACK_DEFAULT   = '#galahad'
    static final String SLACK_OPS       = '#ops'
    static final String SLACK_CODEX     = '#codex'
    static final String SLACK_RESEARCH  = '#research'
    static final String SLACK_HITL      = '#hitl'
}
