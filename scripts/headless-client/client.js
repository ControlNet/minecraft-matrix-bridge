'use strict'
// Test-only offline-mode Minecraft client used by scripts/system_test_all.py.
//
// It joins a local server, reports every chat/system message as a JSON line on stdout,
// waits for a message containing --expect (the bridge's "connected" notice), sends one
// chat line, waits for the server to echo it, and disconnects. The server version is
// detected with a status ping; versions minecraft-protocol cannot speak exit with code 3.
//
// Exit codes: 0 done, 1 timeout, 2 disconnected/kicked/error, 3 unsupported version.

const mc = require('minecraft-protocol')
const minecraftData = require('minecraft-data')

function parseArgs (argv) {
  const args = { host: '127.0.0.1', username: 'Probe', locale: 'en_us', timeoutMs: 60000 }
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i].replace(/^--/, '').replace(/-([a-z])/g, (_, c) => c.toUpperCase())
    args[key] = argv[i + 1]
  }
  for (const required of ['port', 'expect', 'chat']) {
    if (!args[required]) throw new Error(`missing --${required}`)
  }
  args.port = Number(args.port)
  args.timeoutMs = Number(args.timeoutMs)
  return args
}

function emit (event, fields = {}) {
  process.stdout.write(JSON.stringify({ event, ...fields }) + '\n')
}

// Chat components arrive as JSON strings (<1.20.3) or NBT (1.20.3+); flatten both.
function simplifyNbt (value) {
  if (value && typeof value === 'object' && 'type' in value && 'value' in value && Object.keys(value).length <= 2) {
    const inner = value.value
    if (value.type === 'compound') {
      return Object.fromEntries(Object.entries(inner).map(([k, v]) => [k, simplifyNbt(v)]))
    }
    if (value.type === 'list') {
      return (inner.value || []).map((v) => simplifyNbt({ type: inner.type, value: v }))
    }
    return inner
  }
  return value
}

function plainText (component) {
  if (component === undefined || component === null) return ''
  if (typeof component === 'string') {
    try {
      return plainText(JSON.parse(component))
    } catch {
      return component
    }
  }
  if (Array.isArray(component)) return component.map(plainText).join('')
  if (typeof component !== 'object') return String(component)
  const c = simplifyNbt(component)
  if (typeof c !== 'object' || c === null) return String(c)
  let out = ''
  if (typeof c[''] === 'string') out += c['']
  if (typeof c.text === 'string') out += c.text
  if (typeof c.translate === 'string') {
    const args = (c.with || []).map(plainText)
    // Vanilla chat formats are "<%s> %s"; enough to recognise echoed chat lines.
    out += c.translate === 'chat.type.text' && args.length === 2 ? `<${args[0]}> ${args[1]}` : [c.translate, ...args].join(' ')
  }
  if (Array.isArray(c.extra)) out += c.extra.map(plainText).join('')
  return out
}

function main () {
  const args = parseArgs(process.argv.slice(2))
  let finished = false
  const finish = (code, event, fields) => {
    if (finished) return
    finished = true
    emit(event, fields)
    clearTimeout(timer)
    if (client) client.end()
    setTimeout(() => process.exit(code), 500)
  }
  const timer = setTimeout(() => finish(1, 'timeout', { timeoutMs: args.timeoutMs }), args.timeoutMs)
  let client = null

  mc.ping({ host: args.host, port: args.port }, (err, response) => {
    if (err) return finish(2, 'error', { stage: 'ping', message: String(err) })
    const protocol = response.version && response.version.protocol
    const versions = minecraftData.postNettyVersionsByProtocolVersion.pc[protocol] || []
    if (versions.length === 0) {
      return finish(3, 'unsupported', { protocol, name: response.version && response.version.name })
    }
    const version = versions[0].minecraftVersion
    emit('ping', { protocol, server: response.version.name, version })
    // minecraft-data can list a protocol number before it ships packet data for it.
    if (!minecraftData(version) || !minecraftData(version).protocol) {
      return finish(3, 'unsupported', { protocol, name: response.version.name, version })
    }

    client = mc.createClient({
      host: args.host,
      port: args.port,
      username: args.username,
      auth: 'offline',
      version,
      clientSettings: { locale: args.locale }
    })

    let configured = false
    let chatSent = false
    client.on('state', (state) => {
      if (state === mc.states.CONFIGURATION) configured = true
    })
    // NeoForge pings during the configuration phase and waits for the pong, as the
    // vanilla client sends; minecraft-protocol does not answer it on its own.
    client.on('ping', (packet) => client.write('pong', { id: packet.id }))
    client.on('login', () => {
      emit('login', { version })
      if (!configured) {
        // Before 1.20.2 there is no configuration phase; send Client Information in play.
        client.write('settings', {
          locale: args.locale,
          viewDistance: 10,
          chatFlags: 0,
          chatColors: true,
          skinParts: 127,
          mainHand: 1,
          enableTextFiltering: false,
          enableServerListing: true
        })
      }
    })

    const onText = (text) => {
      emit('message', { text })
      if (!chatSent && text.includes(args.expect)) {
        chatSent = true
        setTimeout(() => {
          emit('chat', { text: args.chat })
          client.chat(args.chat)
        }, 1000)
      } else if (chatSent && text.includes(args.chat)) {
        // The server echoed our chat line, so it went through ServerChatEvent.
        setTimeout(() => finish(0, 'done'), 1000)
      }
    }
    client.on('systemChat', (packet) => onText(plainText(packet.formattedMessage)))
    client.on('playerChat', (packet) => onText(
      packet.formattedMessage ? plainText(packet.formattedMessage) : `<${plainText(packet.senderName)}> ${packet.plainMessage}`))

    client.on('kick_disconnect', (packet) => finish(2, 'kicked', { reason: plainText(packet.reason) }))
    client.on('disconnect', (packet) => finish(2, 'kicked', { reason: plainText(packet.reason) }))
    client.on('error', (error) => finish(2, 'error', { stage: 'play', message: String(error) }))
    client.on('end', (reason) => finish(2, 'ended', { reason: String(reason) }))
  })
}

main()
