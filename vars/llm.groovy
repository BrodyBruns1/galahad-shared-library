/**
 * llm — Galahad LLM routing
 *
 *   'fast'   → Ollama /api/generate (qwen3.5:9b)
 *   'medium' → LM Studio /v1/chat/completions (ministral-3-14b-reasoning, falls back to Ollama)
 *   'heavy'  → Codex via valkey-bridge queue
 */

import org.galahad.Config
import groovy.json.JsonOutput

def call(String prompt, String complexity = 'fast') {
    switch (complexity) {
        case 'fast':   return ollama(prompt)
        case 'medium': return lmstudio(prompt)
        case 'heavy':  return codexQueue(prompt)
        default:       return ollama(prompt)
    }
}

def ollama(String prompt, String model = null) {
    model = model ?: Config.OLLAMA_MODEL
    def body = JsonOutput.toJson([
        model:  model,
        prompt: prompt,
        stream: false
    ])
    try {
        def resp = httpRequest(
            url:                "${Config.OLLAMA_URL}/api/generate",
            httpMode:           'POST',
            contentType:        'APPLICATION_JSON',
            requestBody:        body,
            validResponseCodes: '200',
            timeout:            60
        )
        def json = new groovy.json.JsonSlurperClassic().parseText(resp.content)
        return json.response?.trim()
    } catch (e) {
        echo "Ollama error: ${e.message}"
        return null
    }
}

def lmstudio(String prompt, int maxTokens = 2048) {
    def ts = System.currentTimeMillis()
    def bodyFile = "/tmp/lms-req-${ts}.json"
    def respFile = "/tmp/lms-resp-${ts}.json"
    def body = JsonOutput.toJson([
        model:       Config.LMSTUDIO_MODEL,
        messages:    [[role: 'user', content: prompt]],
        temperature: 0.3,
        max_tokens:  maxTokens,
        stream:      false
    ])
    try {
        writeFile file: bodyFile, text: body
        def rc = sh(
            script: "curl -sf -X POST '${Config.LMSTUDIO_URL}/v1/chat/completions' " +
                    "-H 'Content-Type: application/json' " +
                    "--data-binary @${bodyFile} " +
                    "-o ${respFile} -w '%{http_code}' --max-time 300",
            returnStdout: true
        ).trim()
        if (rc != '200') {
            echo "LM Studio returned HTTP ${rc}, falling back to Ollama"
            return ollama(prompt)
        }
        def raw = readFile(file: respFile)
        echo "LM Studio response length: ${raw.length()}"
        def json = new groovy.json.JsonSlurperClassic().parseText(raw)
        def msg = (json['choices'] as List)[0]['message'] as Map
        def content = msg['content']?.toString()?.trim()
        return content ?: msg['reasoning_content']?.toString()?.trim()
    } catch (e) {
        echo "LM Studio error (falling back to Ollama): ${e.message}"
        return ollama(prompt)
    } finally {
        sh "rm -f ${bodyFile} ${respFile} 2>/dev/null || true"
    }
}

def codexQueue(String prompt, String workDir = '/root/work') {
    def body = JsonOutput.toJson([
        prompt:   prompt,
        model:    Config.CODEX_MODEL,
        work_dir: workDir,
        source:   'jenkins'
    ])
    try {
        def resp = httpRequest(
            url:         "${Config.VALKEY_BRIDGE}/task",
            httpMode:    'POST',
            contentType: 'APPLICATION_JSON',
            requestBody: body,
            timeout:     15
        )
        echo "Codex task queued: ${resp.content}"
        return resp.content
    } catch (e) {
        echo "Codex queue error: ${e.message}"
        return null
    }
}

def classify(String text, List<String> intents) {
    return ollama(
        "Classify this text into exactly one of these intents: ${intents.join(', ')}\n\n" +
        "Text: \"${text}\"\n\nRespond with only the intent label, nothing else."
    )?.toLowerCase()?.trim()
}

def summarize(String text, String context = '') {
    def prompt = context
        ? "Context: ${context}\n\nSummarize this in 1-2 concise sentences:\n${text}"
        : "Summarize this in 1-2 concise sentences:\n${text}"
    return ollama(prompt)
}

def narrate(String action, String result) {
    return ollama(
        "You are Galahad, a homelab AI assistant. Summarize this completed action in one plain sentence " +
        "suitable for a voice response. Action: \"${action}\". Result: \"${result}\". Be direct and confident."
    )
}

def explainError(String action, String error) {
    return ollama(
        "You are Galahad, a homelab AI assistant. Explain this error in one plain sentence a non-developer " +
        "can understand. Action attempted: \"${action}\". Error: \"${error.take(500)}\". Be concise."
    )
}

