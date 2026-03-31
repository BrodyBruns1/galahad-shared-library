/**
 * llm — Galahad LLM routing
 *
 * All endpoints use OpenAI-compatible /v1/chat/completions:
 *   'fast'   → Ollama qwen3.5:9b  (classification, summaries, Q&A)
 *   'medium' → LM Studio          (reasoning, reports, pipeline generation)
 *   'heavy'  → Codex via queue    (async, multi-file projects, deep research)
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
        model:       model,
        messages:    [[role: 'user', content: prompt]],
        temperature: 0.3,
        stream:      false
    ])
    try {
        def resp = httpRequest(
            url:                "${Config.OLLAMA_URL}/v1/chat/completions",
            httpMode:           'POST',
            contentType:        'APPLICATION_JSON',
            requestBody:        body,
            validResponseCodes: '200',
            timeout:            60
        )
        def json = new groovy.json.JsonSlurperClassic().parseText(resp.content)
        return json.choices[0].message.content?.trim()
    } catch (e) {
        echo "Ollama error: ${e.message}"
        return null
    }
}

def lmstudio(String prompt, int maxTokens = 2048) {
    def body = JsonOutput.toJson([
        model:       'local-model',
        messages:    [[role: 'user', content: prompt]],
        temperature: 0.3,
        max_tokens:  maxTokens,
        stream:      false
    ])
    try {
        def resp = httpRequest(
            url:                "${Config.LMSTUDIO_URL}/v1/chat/completions",
            httpMode:           'POST',
            contentType:        'APPLICATION_JSON',
            requestBody:        body,
            validResponseCodes: '200',
            timeout:            120
        )
        def json = new groovy.json.JsonSlurperClassic().parseText(resp.content)
        return json.choices[0].message.content?.trim()
    } catch (e) {
        echo "LM Studio error (falling back to Ollama): ${e.message}"
        return ollama(prompt)
    }
}

def codexQueue(String prompt, String workDir = '/root/work') {
    def body = JsonOutput.toJson([
        prompt:   prompt,
        model:    'gpt-4o',
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
