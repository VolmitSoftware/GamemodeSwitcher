import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

async function until(context, predicate, message, timeout = 6000) {
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
  if (!item) return ''
  return text(item.customName) + ' ' + text(item.components?.find(entry => entry.type === 'lore')?.data)
}

async function mode(context, destination, command = `/gsw set ${destination}`) {
  await context.sleep(700)
  context.bot.chat(command)
  await until(context, () => context.bot.game.gameMode === destination, `Expected ${destination}, got ${context.bot.game.gameMode}`)
  await context.sleep(200)
}

async function open(context, command) {
  const opened = context.waitForEvent('windowOpen', () => true, 6000)
  context.bot.chat(command)
  await opened
  await context.sleep(350)
}

async function click(context, slot, button = 0) {
  await context.bot.clickWindow(slot, button, 0)
  await context.sleep(600)
}

export default {
  name: 'spectator-sounds',
  description: 'Verify tracked spectator returns, origin safety, restart persistence, and sound selection.',
  async run(context) {
    const directory = process.env.GSW_QA_PLUGIN_DIR
    context.expect(directory, 'GSW_QA_PLUGIN_DIR is required')
    const configFile = path.join(directory, 'config.toml')
    const sessionFile = path.join(directory, 'data', 'spectator-sessions.toml')
    const playerId = context.bot._client.uuid
    const original = await readFile(configFile, 'utf8')
    if (context.options.command === 'resume') {
      await context.step('tracked spectator session survives server restart', async () => {
        context.expect(context.bot.game.gameMode === 'spectator', 'Saved spectator player mode was lost')
        await mode(context, 'adventure', '/gsw return')
        context.expect(context.bot.entity.position.distanceTo({ x: 100.5, y: 100, z: 100.5 }) < 2, 'Restart return lost entry position')
      })
      return
    }
    let configured = original.replace(/restore-previous-mode\s*=\s*false/, 'restore-previous-mode = true')
      .replace(/return-to-origin\s*=\s*false/, 'return-to-origin = true')
      .replace(/allow-unsafe-return\s*=\s*true/, 'allow-unsafe-return = false')
    context.expect(configured.includes('restore-previous-mode = true') && configured.includes('return-to-origin = true'), 'Spectator options absent')
    await writeFile(configFile, configured)
    await context.sleep(2600)
    await context.command('/difficulty peaceful', /difficulty|already/i, 6000)
    await mode(context, 'creative', '/gamemode creative')
    await context.command('/tp @s 100.5 100 100.5', /teleported/i, 6000)
    await context.command('/fill 96 99 96 104 99 104 minecraft:stone', /filled|blocks/i, 6000)
    await context.command('/fill 96 100 96 104 103 104 minecraft:air', /filled|blocks|no blocks/i, 6000)
    await context.step('Adventure spectator return restores prior mode and origin', async () => {
      await mode(context, 'adventure')
      await mode(context, 'spectator')
      await until(context, async () => (await readFile(sessionFile, 'utf8')).includes(playerId), 'Session was not persisted')
      await context.command('/tp @s 130.5 105 100.5', /teleported/i, 6000)
      const restricted = (await readFile(configFile, 'utf8')).replace('disabled-worlds = []', 'disabled-worlds = ["world"]')
      await writeFile(configFile, restricted)
      await context.sleep(2500)
      await context.command('/gsw return', /disabled|world/i, 6000)
      context.expect(context.bot.game.gameMode === 'spectator', 'Disabled world allowed session return')
      await writeFile(configFile, configured)
      await context.sleep(2500)
      await mode(context, 'adventure', '/gsw return')
      context.expect(context.bot.entity.position.distanceTo({ x: 100.5, y: 100, z: 100.5 }) < 2, 'Player did not return to origin')
    })
    await context.step('unsafe origin keeps Spectator and retains retryable session', async () => {
      await mode(context, 'spectator')
      await context.command('/fill 96 99 96 104 99 104 minecraft:air', /filled|blocks/i, 6000)
      await context.command('/tp @s 130.5 105 100.5', /teleported/i, 6000)
      await context.sleep(700)
      await context.command('/gsw return', /safe|blocked|return.*fail|could not return/i, 6000)
      await context.sleep(1200)
      context.expect(context.bot.game.gameMode === 'spectator', 'Unsafe return exited Spectator')
      context.expect(context.bot.entity.position.x > 125, 'Unsafe return teleported player')
      context.expect((await readFile(sessionFile, 'utf8')).includes(playerId), 'Failed return discarded session')
      await context.command('/fill 96 99 96 104 99 104 minecraft:stone', /filled|blocks/i, 6000)
      await mode(context, 'adventure', '/gsw return')
    })
    await context.step('external mode changes invalidate tracked previous mode', async () => {
      await mode(context, 'spectator')
      await mode(context, 'creative', '/gamemode creative')
      await mode(context, 'spectator', '/gamemode spectator')
      context.bot.chat('/gsw return')
      await context.sleep(1000)
      context.expect(context.bot.game.gameMode === 'spectator', 'Untracked return reused a discarded session')
      await mode(context, 'creative', '/gamemode creative')
    })
    await context.step('sound picker preview does not save and selection persists', async () => {
      await writeFile(configFile, (await readFile(configFile, 'utf8')).replace('sound-enabled = true', 'sound-enabled = false'))
      await context.sleep(2500)
      const previousMode = context.bot.game.gameMode
      await open(context, '/gsw config')
      await until(context, () => context.bot.currentWindow.slots.some(item => /^Switch feedback/.test(itemText(item))), 'Feedback category missing')
      await click(context, context.bot.currentWindow.slots.findIndex(item => /^Switch feedback/.test(itemText(item))))
      await until(context, () => context.bot.currentWindow.slots.some(item => /^Sound key|^Switch sound/.test(itemText(item))), 'Sound entry missing')
      const soundSlot = context.bot.currentWindow.slots.findIndex(item => item?.name === 'jukebox')
      context.expect(soundSlot >= 0, 'Sound picker entry missing')
      await click(context, soundSlot)
      await until(context, () => context.bot.currentWindow.slots[21]?.name === 'amethyst_shard' || /chime/i.test(itemText(context.bot.currentWindow.slots[21])), 'Sound presets missing')
      const before = await readFile(configFile, 'utf8')
      const sound = context.waitForEvent('soundEffectHeard', () => true, 6000)
      await click(context, 21, 1)
      await sound
      context.expect(await readFile(configFile, 'utf8') === before, 'Preview changed config')
      context.expect(context.bot.game.gameMode === previousMode, 'Preview changed player game mode')
      await click(context, 23)
      await until(context, async () => /sound\s*=\s*"minecraft:block.note_block.pling"/.test(await readFile(configFile, 'utf8')), 'Selected preset not saved')
      await context.sleep(600)
      const currentSound = context.waitForEvent('soundEffectHeard', () => true, 6000)
      await click(context, 13)
      await currentSound
      await click(context, 32)
      await until(context, () => context.bot.currentWindow === null, 'Custom sound did not open chat input')
      context.bot.chat('minecraft:block.note_block.chime')
      await until(context, async () => /sound\s*=\s*"minecraft:block.note_block.chime"/.test(await readFile(configFile, 'utf8')), 'Custom sound input was not saved')
      await until(context, () => context.bot.currentWindow !== null, 'Custom input did not return to picker')
      context.bot.closeWindow(context.bot.currentWindow)
    })
    await context.step('prepare persistent spectator session for restart', async () => {
      await mode(context, 'adventure')
      await context.command('/tp @s 100.5 100 100.5', /teleported/i, 6000)
      await mode(context, 'spectator')
      await context.command('/tp @s 130.5 105 100.5', /teleported/i, 6000)
      await until(context, async () => (await readFile(sessionFile, 'utf8')).includes(playerId), 'Restart session not persisted')
    })
  }
}

