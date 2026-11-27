import { Muya } from '@muyajs/core'
import '@muyajs/core/lib/core.css'
import './editor.css'

const host = document.querySelector('#manuscript')
const locale = { name: 'zh-CN', resource: { 'Type / to insert...': '开始书写…' } }

let channel
let editor
let revision
let baseline = ''
let source = ''
let composing = false
let loading = false

const emit = (payload) => channel?.postMessage(JSON.stringify(payload))

function publish() {
    if (!editor || composing || loading) return
    const rendered = editor.getMarkdown().replace(/\n*$/, '')
    if (rendered === baseline) return
    baseline = rendered
    const newline = source.includes('\r\n') ? '\r\n' : '\n'
    const trailing = source.match(/(?:\r?\n)*$/)[0]
    source = rendered.replaceAll('\n', newline) + trailing
    emit({ type: 'edit', revision, text: source })
}

function applyTheme(theme) {
    const style = document.documentElement.style
    style.setProperty('--editor-bg-color', theme.background)
    style.setProperty('--editor-color', theme.foreground)
    style.setProperty('--theme-color', theme.accent)
    style.setProperty('color-scheme', theme.dark ? 'dark' : 'light')
}

function openDocument(message) {
    loading = true
    try {
        revision = message.revision
        source = message.text
        applyTheme(message.theme)
        if (editor) {
            editor.setContent(source, false)
        } else {
            editor = new Muya(host, {
                markdown: source,
                locale,
                fontSize: 18,
                lineHeight: 1.55,
                hideQuickInsertHint: true,
                hideLinkPopup: true,
                spellcheckEnabled: false,
                disableHtml: true,
            })
            editor.init()
            editor.on('content-change', () => queueMicrotask(publish))
        }
        baseline = editor.getMarkdown().replace(/\n*$/, '')
        emit({ type: 'ready', revision })
    } catch (error) {
        console.error(error)
        emit({ type: 'error', revision })
    } finally {
        loading = false
    }
}

host.addEventListener('compositionstart', () => { composing = true })
host.addEventListener('compositionend', () => {
    composing = false
    queueMicrotask(publish)
})
host.addEventListener('blur', publish, true)
host.addEventListener('click', (event) => {
    if (event.target.closest('a')) event.preventDefault()
}, true)

window.addEventListener('message', (event) => {
    if (event.data !== 'qingjian-muya' || channel || event.ports.length !== 1) return
    channel = event.ports[0]
    channel.onmessage = ({ data }) => {
        const message = JSON.parse(data)
        switch (message.type) {
            case 'open':
                openDocument(message)
                break
            case 'theme':
                applyTheme(message.theme)
                break
            case 'flush':
                if (composing) host.querySelector('[contenteditable="true"]')?.blur()
                setTimeout(() => {
                    publish()
                    emit({ type: 'flushed', revision })
                }, 0)
                break
        }
    }
    channel.start()
    emit({ type: 'connected' })
})
