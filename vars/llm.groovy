/**
 * llm — Galahad LLM routing
 *
 * Routes prompts to the right model based on complexity:
 *   'fast'   → Ollama qwen2.5:7b  (< 2s, classification, summaries)
 *   'medium' → LM Studio          (reasoning, reports, code explanation)
 *   'heavy'  → Codex via queue    (async, multi-file projects, deep research)
 *
 * Usage:
 *   def answer = llm.call('What containers are down?', 'fast')
 *   def report = llm.call('Write a summary of...', 'medium')
 *   def taskId = llm.call('Refactor the dashboard JS', 'heavy')
 */

import org.galahad.Config
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

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
            url:              "${Config.OLLAMA_URL}/api/generate",
            httpMode:         'POST',
            contentType:      'APPLICATION_JSON',
            requestBody:      body,
            validResponseCodes: '200',
            timeout:          60
        )
        def json = new JsonSlurper().parseText(resp.content)
        return json.response?.trim()
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
        max_tokens:  maxTokens
    ])
    try {
        def resp = httpRequest(
            url:              "${Config.LMSTUDIO_URL}/v1/chat/completions",
            httpMode:         'POST',
            contentType:      'APPLICATION_JSON',
            requestBody:      body,
            validResponseCodes: '200',
            timeout:          120
        )
        def json = new JsonSlurper().parseText(resp.content)
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
    def intentList = intents.join(', ')
    def prompt = """Classify this text into exactly one of these intents: ${intentList}

Text: "${text}"

Respond with only the intent label, nothing else."""
    return ollama(prompt)?.toLowerCase()?.trim()
}

def summarize(String text, String context = '') {
    def prompt = context
        ? "Context: ${context}\n\nSummarize this in 1-2 concise sentences:\n${text}"
        : "Summarize this in 1-2 concise sentences:\n${text}"
    return ollama(prompt)
}
