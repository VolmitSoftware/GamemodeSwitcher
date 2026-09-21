import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

async function until(context, predicate, message, timeout = 7000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await predicate()) return
    await context.sleep(50)
  }
  context.expect(false, message)
}

function text(value) {
  if (value == null) return ''
  if (typeof value === 'string') {
    try { return text(JSON.parse(value)) } catch { return value.replace(/§[0-9a-fk-orx]/gi, '') }
  }
  if (Array.isArray(value)) return value.map(text).join('')
  if (typeof value !== 'object') return ''
  if ('type' in value && 'value' in value) return text(value.value)
  return text(value.text) + text(value.extra)
}

function itemText(item) {
  return item ? text(item.customName) + ' ' + text(item.components?.find(entry => entry.type === 'lore')?.data) : ''
}

async function mode(context, destination, command = `/gsw set ${destination}`) {
  await context.sleep(650)
  context.bot.chat(command)
  await until(context, () => context.bot.game.gameMode === destination, `Expected ${destination}, got ${context.bot.game.gameMode}`)
}

export default {
  name: 'unsafe-return',
  description: 'Verify hot-reloaded spectator safety choice and concise configuration edit feedback.',
  async run(context) {
    const directory = process.env.GSW_QA_PLUGIN_DIR
    context.expect(directory, 'GSW_QA_PLUGIN_DIR is required')
    const configFile = path.join(directory, 'config.toml')
    if (context.options.command === 'invalid') {
      await context.step('invalid custom sound reports one prefixed failure without saving', async () => {
        const opened = context.waitForEvent('windowOpen', () => true, 6000)
        context.bot.chat('/gsw config')
        await opened
        await until(context, () => context.bot.currentWindow?.slots.some(item => /^Switch feedback/.test(itemText(item))), 'Feedback category absent')
        await context.bot.clickWindow(context.bot.currentWindow.slots.findIndex(item => /^Switch feedback/.test(itemText(item))), 0, 0)
        await until(context, () => context.bot.currentWindow?.slots.some(item => item?.name === 'jukebox'), 'Sound entry absent')
        await context.bot.clickWindow(context.bot.currentWindow.slots.findIndex(item => item?.name === 'jukebox'), 0, 0)
        await until(context, () => /custom/i.test(itemText(context.bot.currentWindow?.slots[32])), 'Sound picker absent')
        await context.bot.clickWindow(32, 0, 0)
        await until(context, () => context.bot.currentWindow == null, 'Custom prompt absent')
        const before = await readFile(configFile, 'utf8')
        const messages = []
        const listener = message => messages.push(message)
        context.bot.on('messagestr', listener)
        try {
          context.bot.chat('Not a sound key!')
          await until(context, () => messages.some(message => /unable|could not|invalid/i.test(message)), 'Invalid sound did not report failure')
          await until(context, () => context.bot.currentWindow != null, 'Failure did not return to menu')
          await context.sleep(700)
          const failures = messages.filter(message => /unable|could not|invalid/i.test(message))
          context.expect(failures.length === 1 && (failures[0].match(/GamemodeSwitcher/g) ?? []).length === 1, 'Expected one prefixed failure', { messages })
          context.expect(!messages.some(message => /changed from|Saving configuration|Loading configuration|Saved .*Changes apply automatically/i.test(message)), 'Invalid edit emitted success or extra chatter', { messages })
          context.expect(await readFile(configFile, 'utf8') === before, 'Invalid sound changed config')
          context.report.invalidMessages = messages
        } finally {
          context.bot.removeListener('messagestr', listener)
        }
      })
      return
    }
    const sessionFile = path.join(directory, 'data', 'spectator-sessions.toml')
    const id = context.bot._client.uuid
    const original = await readFile(configFile, 'utf8')
    context.expect(/allow-unsafe-return\s*=\s*true/.test(original), 'Unsafe return is not enabled by default')
    async function configure(key, value) {
      const before = await readFile(configFile, 'utf8')
      const changed = before.replace(new RegExp(`^${key}\\s*=.*$`, 'm'), `${key} = ${value}`)
      context.expect(changed !== before, `Setting ${key} did not change`)
      await writeFile(configFile, changed)
      await context.sleep(2500)
    }
    await configure('restore-previous-mode', 'true')
    await configure('return-to-origin', 'true')
    await mode(context, 'creative', '/gamemode creative')
    await context.command('/tp @s 100.5 100 100.5', /teleported/i, 6000)
    await context.command('/fill 96 99 96 104 99 104 minecraft:stone', /filled|blocks/i, 6000)
    await context.command('/fill 96 100 96 104 103 104 minecraft:air', /filled|blocks|no blocks/i, 6000)
    await context.step('default unsafe return allows a removed floor', async () => {
      await mode(context, 'spectator')
      await context.command('/fill 96 99 96 104 99 104 minecraft:air', /filled|blocks/i, 6000)
      await context.command('/tp @s 130.5 105 100.5', /teleported/i, 6000)
      await mode(context, 'creative', '/gsw return')
      context.expect(context.bot.entity.position.distanceTo({ x: 100.5, y: 100, z: 100.5 }) < 2, 'Unsafe return lost saved origin')
      context.expect(context.bot.health > 0, 'Creative return caused death')
    })
    await context.step('same saved session follows hot-reloaded safety setting', async () => {
      await context.command('/fill 96 99 96 104 99 104 minecraft:stone', /filled|blocks/i, 6000)
      await context.command('/tp @s 100.5 100 100.5', /teleported/i, 6000)
      await mode(context, 'spectator')
      await until(context, async () => (await readFile(sessionFile, 'utf8')).includes(id), 'Entry session absent')
      const saved = await readFile(sessionFile, 'utf8')
      await context.command('/fill 96 99 96 104 99 104 minecraft:air', /filled|blocks/i, 6000)
      await context.command('/tp @s 130.5 105 100.5', /teleported/i, 6000)
      await configure('allow-unsafe-return', 'false')
      await context.command('/gsw return', /safe|return.*fail|could not return/i, 6000)
      await context.sleep(700)
      context.expect(context.bot.game.gameMode === 'spectator', 'Safe-only return changed mode')
      context.expect(context.bot.entity.position.x > 125, 'Safe-only return moved player')
      context.expect(await readFile(sessionFile, 'utf8') === saved, 'Rejected return changed saved session')
      await configure('allow-unsafe-return', 'true')
      await mode(context, 'creative', '/gsw return')
      context.expect(context.bot.entity.position.distanceTo({ x: 100.5, y: 100, z: 100.5 }) < 2, 'Hot-reloaded unsafe return lost origin')
    })
    await context.step('unsafe return still respects disabled worlds', async () => {
      await mode(context, 'spectator')
      await context.command('/tp @s 130.5 105 100.5', /teleported/i, 6000)
      await configure('disabled-worlds', '["world"]')
      await context.command('/gsw return', /disabled|world/i, 6000)
      context.expect(context.bot.game.gameMode === 'spectator' && context.bot.entity.position.x > 125, 'World restriction bypassed')
      await configure('disabled-worlds', '[]')
      await mode(context, 'creative', '/gsw return')
    })
    await context.step('configuration toggle emits one prefixed change message', async () => {
      const messages = []
      const listener = message => messages.push(message)
      context.bot.on('messagestr', listener)
      try {
        const opened = context.waitForEvent('windowOpen', () => true, 6000)
        context.bot.chat('/gsw config')
        await opened
        await until(context, () => context.bot.currentWindow?.slots.some(item => /^Switch feedback/.test(itemText(item))), 'Feedback category absent')
        await context.bot.clickWindow(context.bot.currentWindow.slots.findIndex(item => /^Switch feedback/.test(itemText(item))), 0, 0)
        await until(context, () => context.bot.currentWindow?.slots.some(item => /^Chat messages/.test(itemText(item))), 'Chat setting absent')
        await context.sleep(300)
        const since = messages.length
        await context.bot.clickWindow(context.bot.currentWindow.slots.findIndex(item => /^Chat messages/.test(itemText(item))), 0, 0)
        await until(context, async () => /chat-enabled\s*=\s*false/.test(await readFile(configFile, 'utf8')), 'GUI toggle did not save')
        await context.sleep(1400)
        const changes = messages.slice(since).filter(message => /changed from/i.test(message))
        context.expect(changes.length === 1, 'Expected exactly one configuration change message', { messages })
        context.expect((changes[0].match(/GamemodeSwitcher/g) ?? []).length === 1, 'Change message prefix missing or duplicated', { changes })
        context.expect(!messages.some(message => /Saving configuration|Loading configuration|Saved .*Changes apply automatically/i.test(message)), 'Redundant editor chatter', { messages })
        context.expect(context.bot.currentWindow != null, 'Config editor closed after toggle')
        context.report.configMessages = messages
        context.bot.closeWindow(context.bot.currentWindow)
      } finally {
        context.bot.removeListener('messagestr', listener)
      }
      await configure('chat-enabled', 'true')
    })
    await context.step('unsafe return still requires player permissions', async () => {
      await mode(context, 'spectator')
      await context.command('/tp @s 130.5 105 100.5', /teleported/i, 6000)
      await context.command(`/deop ${context.bot.username}`, /operator|opped|op status/i, 6000)
      await context.command('/gsw return', /permission|not permitted|not allowed|cannot/i, 6000)
      context.expect(context.bot.game.gameMode === 'spectator' && context.bot.entity.position.x > 125, 'Permission restriction bypassed')
      context.expect((await readFile(sessionFile, 'utf8')).includes(id), 'Denied return discarded session')
    })
  }
}

