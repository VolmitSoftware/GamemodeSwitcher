import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
const MODE_TIMEOUT = 5000
const COOLDOWN = 650
async function until(context, predicate, message, timeout = MODE_TIMEOUT) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await predicate()) return
    await context.sleep(50)
  }
  context.expect(false, message)
}

async function applyFile(context, file, contents) {
  await writeFile(file, contents)
  await context.sleep(2400)
}

async function setMode(context, mode) {
  await context.sleep(COOLDOWN)
  await context.command(`/gsw set ${mode}`, /game mode (changed|is already)/i, MODE_TIMEOUT)
  await until(context, () => context.bot.game.gameMode === mode, `Expected ${mode}, got ${context.bot.game.gameMode}`)
}

function swap(context) {
  context.bot._client.write('block_dig', {
    status: 6,
    location: { x: 0, y: 0, z: 0 },
    face: 0,
    sequence: 0
  })
}

async function doubleSwap(context) {
  swap(context)
  await context.sleep(120)
  swap(context)
}

async function openWindow(context, command, size) {
  const opened = context.waitForEvent('windowOpen', () => true, MODE_TIMEOUT)
  context.bot.chat(command)
  const [window] = await opened
  context.expect(window.inventoryStart === size, `Expected ${size} menu slots`, { slots: window.inventoryStart })
  await context.sleep(200)
  return window
}

async function closeWindow(context) {
  if (context.bot.currentWindow) context.bot.closeWindow(context.bot.currentWindow)
  await until(context, () => context.bot.currentWindow === null, 'Inventory did not close')
}

async function clickWindowTransition(context, slot, button = 0, mode = 0) {
  const opened = context.waitForEvent('windowOpen', () => true, MODE_TIMEOUT)
  await context.bot.clickWindow(slot, button, mode)
  await opened
  await context.sleep(150)
}

function componentText(component) {
  if (component === null || component === undefined) return ''
  if (typeof component === 'string') {
    try {
      return componentText(JSON.parse(component))
    } catch {
      return component.replace(/§[0-9a-fk-orx]/gi, '')
    }
  }
  if (Array.isArray(component)) return component.map(componentText).join('')
  if (typeof component !== 'object') return ''
  if ('type' in component && 'value' in component) return componentText(component.value)
  return componentText(component.text) + componentText(component.extra)
}

function itemText(item) {
  if (!item) return ''
  const lore = item.components?.find(component => component.type === 'lore')?.data
    ?? item.nbt?.value?.display?.value?.Lore?.value?.value ?? []
  return [componentText(item.customName), ...lore.map(componentText)].join(' ')
}

function feedbackRecorder(context) {
  const packets = []
  const windows = []
  function packet(data, metadata) {
    const name = metadata.name
    if (name === 'system_chat' || name === 'action_bar') {
      packets.push({ channel: name === 'action_bar' || data.isActionBar ? 'actionbar' : 'chat', text: componentText(data.text ?? data.content) })
    } else if (name === 'set_title_text' || name === 'set_title_subtitle') {
      packets.push({ channel: name === 'set_title_text' ? 'title' : 'subtitle', text: componentText(data.text) })
    } else if (name === 'set_title_time') {
      packets.push({ channel: 'title-time', fadeIn: data.fadeIn, stay: data.stay, fadeOut: data.fadeOut })
    } else if (name === 'clear_titles') {
      packets.push({ channel: 'title-clear', reset: data.reset })
    } else if (name === 'sound_effect' || name === 'entity_sound_effect' || name === 'named_sound_effect') {
      const key = data.sound?.data?.soundName ?? data.soundEvent?.resource ?? data.soundName
        ?? context.bot.registry.sounds[data.sound?.soundId ?? data.soundId]?.name
      packets.push({ channel: 'sound', key: key?.includes(':') ? key : `minecraft:${key}`, category: data.soundCategory, volume: data.volume, pitch: data.pitch })
    }
  }
  function opened(window) {
    windows.push({ id: window.id, title: componentText(window.title), slots: window.inventoryStart })
  }
  context.bot._client.on('packet', packet)
  context.bot.on('windowOpen', opened)
  return {
    mark: () => ({ packet: packets.length, window: windows.length }),
    capture(name, mark) {
      const observation = { name, packets: packets.slice(mark.packet), windows: windows.slice(mark.window) }
      for (const value of observation.packets) {
        context.expect(!/<\/?[a-z][^>]*>/i.test(value.text ?? ''), `${name} exposed a formatting tag`, value)
      }
      context.report.feedbackObservations ??= []
      context.report.feedbackObservations.push(observation)
      return observation
    },
    stop() {
      context.bot._client.removeListener('packet', packet)
      context.bot.removeListener('windowOpen', opened)
    }
  }
}

async function configurationChecks(context, configFile, originalConfig) {
  const omitted = originalConfig.replace(/^\[feedback\][\s\S]*?(?=^\[|$(?![\s\S]))/m, '')
  context.expect(!omitted.includes('[feedback]'), 'Test configuration still has a feedback section')
  await applyFile(context, configFile, omitted)
  await context.step('all feedback controls appear when their whole TOML section is omitted', async () => {
    await openWindow(context, '/gsw config', 54)
    await until(context, () => /^General\b/i.test(itemText(context.bot.currentWindow?.slots[19])), 'Configuration categories did not load')
    await clickWindowTransition(context, 29)
    const entries = context.bot.currentWindow.slots.slice(0, 45).filter(item => item && item.name !== 'black_stained_glass_pane').map(itemText)
    const expected = ['Chat messages', 'Action bar popup', 'Title popup', 'Popup duration', 'Switch sound', 'Sound key', 'Sound volume', 'Sound pitch', 'Fly on entering Creative']
    context.expect(entries.length === expected.length, 'Missing default feedback controls', { entries })
    for (const label of expected) context.expect(entries.some(text => text.startsWith(label)), `Missing ${label}`, { entries })
    context.report.defaultControls = entries
  })
  await context.step('editing an omitted default persists only that option and preserves existing comments', async () => {
    const slot = context.bot.currentWindow.slots.slice(0, 45).findIndex(item => itemText(item).startsWith('Title popup'))
    await clickWindowTransition(context, slot)
    await until(context, async () => /title-enabled[^\r\n]*false/.test(await readFile(configFile, 'utf8')), 'Missing Title setting was not inserted')
    const saved = await readFile(configFile, 'utf8')
    for (const line of omitted.split(/\r?\n/).filter(line => line.startsWith('#'))) context.expect(saved.includes(line), 'An existing explanatory comment was lost', { line })
    context.expect(!/chat-enabled\s*=/.test(saved), 'Saving one default materialized other feedback defaults')
    await closeWindow(context)
  })
  await applyFile(context, configFile, originalConfig)
}

export default {
  name: 'gamemode-preferences',
  description: 'Validate live gesture help, complete configuration controls, personal feedback, and unavailable mode reasons.',
  async run(context) {
    const directory = process.env.GSW_QA_PLUGIN_DIR
    context.expect(directory, 'GSW_QA_PLUGIN_DIR is required')
    const configFile = path.join(directory, 'config.toml')
    const preferencesFile = path.join(directory, 'data', 'player-preferences.toml')
    const originalConfig = await readFile(configFile, 'utf8')
    const recorder = feedbackRecorder(context)
    async function selector() {
      await closeWindow(context)
      await openWindow(context, '/gsw menu', 54)
    }
    async function personal() {
      await selector()
      await clickWindowTransition(context, 40)
      await until(context, () => /Chat/i.test(itemText(context.bot.currentWindow?.slots[19])), 'Personal feedback controls did not load')
    }
    async function preference(slot, muted) {
      await context.bot.clickWindow(slot, 0, 0)
      const changedSlots = slot === 30 || slot === 32 ? [19, 21, 23, 25] : [slot]
      await until(context, () => changedSlots.every(changed => (muted ? /Muted/i : /server|inherit/i).test(itemText(context.bot.currentWindow?.slots[changed]))), 'Personal feedback save did not refresh')
      await context.sleep(250)
    }
    async function change(mode, name) {
      await closeWindow(context)
      await context.sleep(700)
      const mark = recorder.mark()
      context.bot.chat(`/gsw set ${mode}`)
      await until(context, () => context.bot.game.gameMode === mode, `Expected ${mode}`)
      await context.sleep(450)
      const observed = recorder.capture(name, mark)
      context.expect(observed.windows.length === 0, 'Switching opened an unsolicited inventory', observed)
      return observed
    }
    function successes(observed) {
      return observed.packets.filter(packet => packet.channel === 'sound' ? packet.key === 'minecraft:ui.button.click'
        : ['title', 'subtitle', 'actionbar', 'chat'].includes(packet.channel) && /game mode|survival|creative|adventure|spectator/i.test(packet.text))
    }
    try {
      if (context.options.command === 'limited') {
        await context.step('unavailable mode cards explain missing permission before a click', async () => {
          await selector()
          const text = itemText(context.bot.currentWindow.slots[21])
          context.expect(/permission/i.test(text), 'Creative card omitted its permission reason', { text })
          context.report.permissionTooltip = text
          const previous = context.bot.game.gameMode
          await context.bot.clickWindow(21, 0, 0)
          await context.sleep(500)
          context.expect(context.bot.game.gameMode === previous, 'Denied mode card changed game mode')
        })
        return
      }
      if (context.options.command === 'persisted') {
        await context.step('personal silence survives a full server restart', async () => {
          await personal()
          for (const slot of [19, 21, 23, 25]) context.expect(/Muted/i.test(itemText(context.bot.currentWindow.slots[slot])), 'Saved mute state was lost')
          await closeWindow(context)
          const target = context.bot.game.gameMode === 'creative' ? 'survival' : 'creative'
          const observed = await change(target, 'persisted-silence')
          context.expect(successes(observed).length === 0, 'Restart lost personal silence', observed)
        })
        return
      }
      await context.command('/gsw language self en_US', /English|en_US/i, MODE_TIMEOUT)
      await context.command('/gsw toggle true', /gestures enabled/i, MODE_TIMEOUT)
      context.bot.chat('/gamemode survival')
      await until(context, () => context.bot.game.gameMode === 'survival', 'Could not prepare Survival')
      await context.step('gesture help shows the configured Adventure route and client rebinding instructions', async () => {
        await selector()
        const text = itemText(context.bot.currentWindow.slots[30])
        context.expect(/Hold Sneak.*Adventure/i.test(text), 'Adventure gesture missing', { text })
        context.expect(/Key Binds|Controls/i.test(text), 'Rebinding instructions missing', { text })
        context.report.initialGestureHelp = text
        await closeWindow(context)
      })
      await context.step('gesture hints follow a changed destination without a restart', async () => {
        const updated = originalConfig.replace(/(\[gestures\.normal\]\r?\nsurvival\s*=\s*)"CREATIVE"/, '$1"ADVENTURE"')
        context.expect(updated !== originalConfig, 'Test destination was not changed')
        await applyFile(context, configFile, updated)
        await selector()
        const text = itemText(context.bot.currentWindow.slots[30])
        context.expect(/Double-tap Swap Item With Offhand: Adventure/i.test(text), 'Normal gesture help did not hot update', { text })
        context.report.updatedGestureHelp = text
        await closeWindow(context)
        await applyFile(context, configFile, originalConfig)
      })
      await context.step('Spectator help resolves its configured exit destination', async () => {
        await applyFile(context, configFile, originalConfig.replace('spectator-exit = "CREATIVE"', 'spectator-exit = "ADVENTURE"'))
        context.bot.chat('/gamemode spectator')
        await until(context, () => context.bot.game.gameMode === 'spectator', 'Could not prepare Spectator')
        await selector()
        const text = itemText(context.bot.currentWindow.slots[30])
        context.expect(/Press Sneak three separate times: Adventure/i.test(text), 'Spectator exit help did not resolve Adventure', { text })
        await closeWindow(context)
        context.bot.chat('/gamemode survival')
        await until(context, () => context.bot.game.gameMode === 'survival', 'Could not restore Survival')
        await applyFile(context, configFile, originalConfig)
      })
      await configurationChecks(context, configFile, originalConfig)
      await context.step('muting chat alone retains the inherited title and action bar', async () => {
        await personal()
        await preference(19, true)
        const observed = await change('creative', 'chat-only-mute')
        context.expect(!observed.packets.some(packet => packet.channel === 'chat' && /game mode/i.test(packet.text)), 'Chat mute did not suppress success chat', observed)
        for (const channel of ['title', 'actionbar']) context.expect(observed.packets.some(packet => packet.channel === channel && /Creative/i.test(packet.text)), `Chat mute suppressed ${channel}`, observed)
        await change('survival', 'chat-only-mute-survival')
      })
      await context.step('personal silence suppresses every success channel without changing server defaults', async () => {
        await personal()
        await preference(30, true)
        for (const slot of [19, 21, 23, 25]) context.expect(/Muted/i.test(itemText(context.bot.currentWindow.slots[slot])), 'Silence all missed a channel')
        const observed = await change('creative', 'personal-silence')
        context.expect(successes(observed).length === 0, 'Muted player received switch feedback', observed)
        context.expect(await readFile(configFile, 'utf8') === originalConfig, 'Personal silence changed server config')
        const saved = await readFile(preferencesFile, 'utf8')
        for (const key of ['chat', 'action-bar', 'title', 'sound']) context.expect(saved.includes(`${key}-muted = true`), `Missing saved ${key} mute`)
      })
      await context.step('a quiet player still sees a blocked-world reason and unavailable menu labels', async () => {
        await applyFile(context, configFile, originalConfig.replace('disabled-worlds = []', 'disabled-worlds = ["world"]'))
        await selector()
        const text = itemText(context.bot.currentWindow.slots[19])
        context.expect(/unavailable.*world/i.test(text), 'World restriction missing from mode tooltip', { text })
        await closeWindow(context)
        const mark = recorder.mark()
        context.bot.chat('/gsw set survival')
        await context.sleep(600)
        const observed = recorder.capture('quiet-world-denial', mark)
        context.expect(context.bot.game.gameMode === 'creative', 'World restriction did not block switching')
        context.expect(observed.packets.some(packet => packet.channel === 'actionbar' && /unavailable.*world/i.test(packet.text)), 'Quiet mode hid the failure reason', observed)
        context.expect(!observed.packets.some(packet => packet.channel === 'chat' && /unavailable/i.test(packet.text)), 'Quiet failure polluted chat', observed)
        await applyFile(context, configFile, originalConfig)
      })
      await context.step('reset restores inherited feedback and later server setting changes take effect', async () => {
        await personal()
        await preference(32, false)
        const observed = await change('survival', 'inherit-defaults')
        for (const channel of ['chat', 'title', 'actionbar']) context.expect(observed.packets.some(packet => packet.channel === channel && /Survival/i.test(packet.text)), `Inherited ${channel} missing`, observed)
        await applyFile(context, configFile, originalConfig.replace('title-enabled = true', 'title-enabled = false'))
        const changed = await change('creative', 'inherit-updated-defaults')
        context.expect(!changed.packets.some(packet => packet.channel === 'title' && packet.text), 'Inherited title ignored server change', changed)
        context.expect(changed.packets.some(packet => packet.channel === 'actionbar' && /Creative/i.test(packet.text)), 'Other inherited channels were muted', changed)
        await applyFile(context, configFile, originalConfig)
      })
      await context.step('an open menu refreshes its global-disabled explanation after a hot edit', async () => {
        await selector()
        await applyFile(context, configFile, originalConfig.replace('[general]\r\nenabled = true', '[general]\r\nenabled = false').replace(/(\[general\][\s\S]*?^enabled\s*=\s*)true/m, '$1false'))
        await until(context, () => /switching is disabled/i.test(itemText(context.bot.currentWindow?.slots[19])), 'Open menu did not refresh global restriction')
        await closeWindow(context)
        await applyFile(context, configFile, originalConfig)
      })
      await context.step('cooldown reason expires in the open menu without closing it', async () => {
        await applyFile(context, configFile, originalConfig.replace('cooldown-millis = 500', 'cooldown-millis = 3000'))
        await change('survival', 'cooldown-start')
        await selector()
        context.expect(/wait/i.test(itemText(context.bot.currentWindow.slots[21])), 'Cooldown explanation missing')
        await until(context, () => !/wait/i.test(itemText(context.bot.currentWindow?.slots[21])), 'Expired cooldown did not refresh', 6000)
        await closeWindow(context)
        await applyFile(context, configFile, originalConfig)
      })
      await context.step('personal silence is saved for the restart check', async () => {
        await personal()
        await preference(30, true)
        await closeWindow(context)
      })
    } finally {
      recorder.stop()
      await closeWindow(context)
      await applyFile(context, configFile, originalConfig)
    }
  }
}
