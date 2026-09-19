import { readFile, readdir, rm, writeFile } from 'node:fs/promises'
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

async function editLanguageEntry(context, replacement) {
  await openWindow(context, '/gsw language server edit en_US', 54)
  await until(context, () => context.bot.currentWindow?.slots[49]?.name === 'compass', 'Language catalogue search did not load')
  await context.bot.clickWindow(49, 0, 0)
  await until(context, () => context.bot.currentWindow === null, 'Language search prompt did not open')
  const results = context.waitForEvent('windowOpen', () => true, MODE_TIMEOUT)
  context.bot.chat('mode.creative')
  await results
  await context.sleep(150)
  await context.bot.clickWindow(0, 0, 0)
  await until(context, () => context.bot.currentWindow === null, 'Translation input prompt did not open')
  const saved = context.waitForEvent('windowOpen', () => true, MODE_TIMEOUT)
  context.bot.chat(replacement)
  await saved
  await closeWindow(context)
}

function describeItem(item) {
  return item === null ? null : {
    name: item.name,
    customName: item.customName,
    displayText: itemText(item),
    count: item.count,
    nbt: item.nbt,
    components: item.components
  }
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
  return [componentText(item.customName), ...lore.map(componentText)].join('\n')
}

async function submitNamedSetting(context, pattern, value) {
  const window = context.bot.currentWindow
  const slot = window.slots.slice(0, window.inventoryStart).findIndex(item => pattern.test(itemText(item)))
  context.expect(slot >= 0, `No setting matches ${pattern}`)
  await context.bot.clickWindow(slot, 0, 4)
  await until(context, () => context.bot.currentWindow === null, 'Configuration input prompt did not open')
  const reopened = context.waitForEvent('windowOpen', () => true, MODE_TIMEOUT)
  context.bot.chat(value)
  await reopened
  await context.sleep(200)
}

async function inventoryProtection(context, name) {
  const window = context.bot.currentWindow
  const filler = window.slots.slice(0, window.inventoryStart).findIndex(item => item?.name === 'black_stained_glass_pane')
  context.expect(filler >= 0, `${name} has no standard background filler`)
  await context.bot.clickWindow(filler, 0, 0)
  await context.sleep(200)
  context.expect(window.selectedItem === null, `${name} filler was taken onto the cursor`)
  await context.bot.clickWindow(filler, 0, 1)
  await context.sleep(200)
  context.expect(window.selectedItem === null, `${name} shift-click left a cursor item`)
  const stoneSlot = window.slots.findIndex((item, index) => index >= window.inventoryStart && item?.name === 'stone')
  context.expect(stoneSlot >= 0, `${name} lost the player's test stone`)
  await context.bot.clickWindow(stoneSlot, 0, 1)
  await context.sleep(200)
  context.expect(window.slots[stoneSlot]?.name === 'stone' && window.slots[stoneSlot]?.count === 1,
    `${name} accepted a player inventory shift-click`)
  context.expect(!window.slots.slice(0, window.inventoryStart).some(item => item?.name === 'stone'),
    `${name} accepted a stone into its controls`)
  const items = context.bot.inventory.items()
  context.expect(items.length === 1 && items[0].name === 'stone' && items[0].count === 1,
    `${name} leaked or duplicated an inventory item`, { items: items.map(describeItem) })
}

async function menuChecks(context) {
  const directory = process.env.GSW_QA_PLUGIN_DIR
  context.expect(directory !== undefined, 'Set GSW_QA_PLUGIN_DIR to this isolated instance plugin data folder')
  const configFile = path.join(directory, 'config.toml')
  const originalConfig = await readFile(configFile, 'utf8')
  const originalCooldown = Number(originalConfig.match(/^cooldown-millis\s*=\s*(\d+)/m)?.[1])
  context.expect(Number.isFinite(originalCooldown), 'Could not read the initial cooldown')
  async function checkCooldown(expected) {
    await until(context, async () => {
      const source = await readFile(configFile, 'utf8')
      return Number(source.match(/^cooldown-millis\s*=\s*(\d+)/m)?.[1]) === expected
    }, `Configuration cooldown was not saved as ${expected}`)
  }
  async function configurationRoot() {
    await openWindow(context, '/gsw config', 54)
    await until(context, () => /^General\b/i.test(itemText(context.bot.currentWindow?.slots[19])), 'Configuration categories did not load')
  }
  try {
    await context.command('/gsw language self en_US', /en_US|English/i, MODE_TIMEOUT)
    await context.command('/gsw toggle enabled=true', /gestures enabled/i, MODE_TIMEOUT)
    await context.command('/clear', /removed|no items/i, MODE_TIMEOUT)
    await setMode(context, 'survival')
    await context.command('/give @s stone 1', /given|gave/i, MODE_TIMEOUT)
    await until(context, () => context.bot.inventory.items().some(item => item.name === 'stone'), 'Test stone was not received')

    await context.step('selector uses the standard six-row background and separated controls', async () => {
      const window = await openWindow(context, '/gsw menu', 54)
      context.expect(window.slots.slice(0, 54).every(item => item !== null && item !== undefined), 'Selector has unfilled background slots')
      const expected = { 19: 'iron_pickaxe', 21: 'grass_block', 23: 'map', 25: 'ender_eye', 53: 'barrier' }
      for (const [slot, material] of Object.entries(expected)) {
        context.expect(window.slots[Number(slot)]?.name === material, `Selector slot ${slot} should contain ${material}`)
      }
      for (const slot of [13, 28, 30, 32, 34]) {
        context.expect(window.slots[slot]?.name !== 'black_stained_glass_pane', `Selector control ${slot} is missing`)
      }
      context.expect(/selected/i.test(itemText(window.slots[19])), 'Current survival mode is not marked selected')
      context.expect(!/selected/i.test(itemText(window.slots[21])), 'Unselected creative mode is marked selected')
      captureWindow(context, 'selector-survival')
    })

    await context.step('selector blocks taking controls and shifting player items into the menu', async () => {
      await inventoryProtection(context, 'Selector')
    })

    await context.step('selector updates its selected mode and status after a mode change', async () => {
      await clickWindowTransition(context, 21)
      await until(context, () => context.bot.game.gameMode === 'creative', 'Selector did not change the game mode')
      context.expect(/selected/i.test(itemText(context.bot.currentWindow.slots[21])), 'Creative selection did not update')
      context.expect(!/selected/i.test(itemText(context.bot.currentWindow.slots[19])), 'Previous survival selection remained active')
      context.expect(/Creative/i.test(itemText(context.bot.currentWindow.slots[13])), 'Status control has a stale game mode')
      captureWindow(context, 'selector-creative')
    })

    await context.step('personal gesture toggle refreshes its state and remains separate from mode controls', async () => {
      await clickWindowTransition(context, 28)
      context.expect(/disabled/i.test(itemText(context.bot.currentWindow.slots[28])), 'Gesture control did not show disabled')
      await clickWindowTransition(context, 28)
      context.expect(/enabled/i.test(itemText(context.bot.currentWindow.slots[28])), 'Gesture control did not return to enabled')
    })

    await context.step('selector opens centered configuration categories in documented order', async () => {
      await clickWindowTransition(context, 34)
      await until(context, () => /^General\b/i.test(itemText(context.bot.currentWindow?.slots[19])), 'Configuration categories did not load')
      const window = context.bot.currentWindow
      const labels = { 19: 'General', 21: 'Statistics', 23: 'Gesture controls', 25: 'World restrictions', 29: 'Switch feedback', 31: 'Diagnostics', 33: 'Languages' }
      context.expect(window.slots.slice(0, 54).every(item => item !== null && item !== undefined), 'Configuration root has unfilled background slots')
      for (const [slot, label] of Object.entries(labels)) {
        context.expect(itemText(window.slots[Number(slot)]).startsWith(label), `Expected ${label} at configuration slot ${slot}`)
      }
      context.expect(window.slots[45]?.name === 'arrow', 'Configuration root does not expose Back')
      context.expect(window.slots[53]?.name === 'barrier', 'Configuration root does not expose Close')
      captureWindow(context, 'configuration-categories')
    })

    await context.step('configuration protects controls and player inventory items', async () => {
      await inventoryProtection(context, 'Configuration')
    })

    await context.step('nested settings and root Back return to their respective parents', async () => {
      await clickWindowTransition(context, 19)
      context.expect(/^Game mode switching\b/i.test(itemText(context.bot.currentWindow.slots[0])), 'General category did not open its switching setting')
      context.expect(/language/i.test(itemText(context.bot.currentWindow.slots[1])), 'General category did not open its language setting')
      captureWindow(context, 'configuration-general')
      await clickWindowTransition(context, 45)
      context.expect(/^General\b/i.test(itemText(context.bot.currentWindow.slots[19])), 'Nested Back did not return to categories')
      await clickWindowTransition(context, 45)
      context.expect(context.bot.currentWindow.slots[21]?.name === 'grass_block', 'Root Back did not return to the mode selector')
      await closeWindow(context)
    })

    await context.step('configuration boolean controls save and hot apply without a reload command', async () => {
      await configurationRoot()
      await clickWindowTransition(context, 19)
      await clickWindowTransition(context, 0)
      await closeWindow(context)
      await context.command('/gsw status', /Switching:.*Disabled/i, MODE_TIMEOUT)
      await context.command('/gsw set survival', /switching is disabled/i, MODE_TIMEOUT)
      context.expect(context.bot.game.gameMode === 'creative', 'Disabled setting permitted a mode change')
      await configurationRoot()
      await clickWindowTransition(context, 19)
      await clickWindowTransition(context, 0)
      await closeWindow(context)
      await context.command('/gsw status', /Switching:.*Enabled/i, MODE_TIMEOUT)
    })

    await context.step('numeric controls support small and shifted increments with right-click decrement', async () => {
      await configurationRoot()
      await clickWindowTransition(context, 23)
      context.expect(/cooldown/i.test(itemText(context.bot.currentWindow.slots[2])), 'Gesture cooldown is not the third setting')
      captureWindow(context, 'configuration-gestures')
      await clickWindowTransition(context, 2)
      await checkCooldown(originalCooldown + 50)
      await clickWindowTransition(context, 2, 0, 1)
      await checkCooldown(originalCooldown + 550)
      await clickWindowTransition(context, 2, 1)
      await checkCooldown(originalCooldown + 500)
    })

    await context.step('drop-key chat editing saves an exact numeric setting and cancel preserves it', async () => {
      await submitNamedSetting(context, /^.*cooldown/i, '750')
      await checkCooldown(750)
      await submitNamedSetting(context, /^.*cooldown/i, 'cancel')
      await checkCooldown(750)
      context.expect(/cooldown/i.test(itemText(context.bot.currentWindow.slots[2])), 'Cancel did not return to the same category')
      await submitNamedSetting(context, /^.*cooldown/i, String(originalCooldown))
      await checkCooldown(originalCooldown)
      await clickWindowTransition(context, 45)
    })

    await context.step('Languages shortcut opens the shared editor and Back returns to configuration', async () => {
      await clickWindowTransition(context, 33)
      await until(context, () => context.bot.currentWindow?.slots[53]?.name === 'barrier', 'Language editor did not open')
      captureWindow(context, 'configuration-language-shortcut')
      await clickWindowTransition(context, 45)
      await until(context, () => /^General\b/i.test(itemText(context.bot.currentWindow?.slots[19])), 'Language Back did not return to configuration')
      await context.bot.clickWindow(53, 0, 0)
      await until(context, () => context.bot.currentWindow === null, 'Configuration Close did not close the inventory')
    })

    await context.step('selector Close preserves player items without leaking menu controls', async () => {
      await openWindow(context, '/gsw menu', 54)
      await context.bot.clickWindow(53, 0, 0)
      await until(context, () => context.bot.currentWindow === null, 'Selector Close did not close the inventory')
      const items = context.bot.inventory.items()
      context.expect(items.length === 1 && items[0].name === 'stone' && items[0].count === 1,
        'Closing menus leaked or duplicated items', { items: items.map(describeItem) })
    })
  } finally {
    await closeWindow(context)
    await applyFile(context, configFile, originalConfig)
    context.bot.clearControlStates()
  }
}

async function limitedMenuChecks(context) {
  try {
    await context.step('limited player sees selected Survival and unavailable destination modes', async () => {
      const window = await openWindow(context, '/gsw menu', 54)
      context.expect(context.bot.game.gameMode === 'survival', 'Limited player should start in Survival')
      context.expect(/selected/i.test(itemText(window.slots[19])), 'Survival is not marked selected for the limited player')
      for (const slot of [21, 23, 25]) {
        context.expect(/cannot select|unavailable|not permitted/i.test(itemText(window.slots[slot])), `Mode slot ${slot} does not explain its permission restriction`)
      }
      context.expect(window.slots[34]?.name === 'black_stained_glass_pane', 'Configuration shortcut is visible without its permission')
      captureWindow(context, 'selector-limited-permissions')
    })
    await context.step('unavailable destination clicks cannot change mode or give inventory items', async () => {
      for (const slot of [21, 23, 25]) {
        await clickWindowTransition(context, slot)
        context.expect(context.bot.game.gameMode === 'survival', `Restricted mode slot ${slot} changed the game mode`)
      }
      context.expect(context.bot.inventory.items().length === 0, 'Restricted mode controls leaked into inventory')
      await closeWindow(context)
    })
    await context.step('limited menu access does not grant configuration editor access', async () => {
      await context.command('/gsw config', /permission|not permitted|not allowed/i, MODE_TIMEOUT)
      context.expect(context.bot.currentWindow === null, 'Restricted configuration command opened the editor')
    })
  } finally {
    await closeWindow(context)
    context.bot.clearControlStates()
  }
}

async function revokedMenuChecks(context) {
  try {
    await context.step('configuration Back closes after mode-menu permission is revoked', async () => {
      await openWindow(context, '/gsw config', 54)
      await until(context, () => /^General\b/i.test(itemText(context.bot.currentWindow?.slots[19])), 'Configuration categories did not load')
      context.expect(context.bot.currentWindow.slots[45]?.name === 'arrow', 'Initial configuration root is missing its menu Back control')
      await context.command(`/deop ${context.bot.username}`, /operator|opped|op status/i, MODE_TIMEOUT)
      await context.bot.clickWindow(45, 0, 0)
      await until(context, () => context.bot.currentWindow === null, 'Revoked menu permission left a stranded configuration inventory')
    })
    await context.step('retained configuration permission works without exposing a forbidden menu return', async () => {
      await openWindow(context, '/gsw config', 54)
      await until(context, () => /^General\b/i.test(itemText(context.bot.currentWindow?.slots[19])), 'Retained configuration permission did not open categories')
      context.expect(context.bot.currentWindow.slots[45]?.name === 'black_stained_glass_pane', 'Configuration exposes a return to an unavailable mode menu')
      captureWindow(context, 'configuration-without-selector-permission')
      await context.bot.clickWindow(53, 0, 0)
      await until(context, () => context.bot.currentWindow === null, 'Retained configuration Close did not close the inventory')
    })
  } finally {
    await closeWindow(context)
  }
}

async function configHelpChecks(context) {
  const directory = process.env.GSW_QA_PLUGIN_DIR
  context.expect(directory !== undefined, 'Set GSW_QA_PLUGIN_DIR to this isolated instance plugin data folder')
  const configFile = path.join(directory, 'config.toml')
  const originalConfig = await readFile(configFile, 'utf8')
  const comments = originalConfig.split(/\r?\n/).filter(line => /^\s*#/.test(line))
  const properties = await readFile(path.join(directory, '..', '..', 'server.properties'), 'utf8')
  const worldName = properties.match(/^level-name=(.+)$/m)?.[1].trim() ?? 'world'

  async function restrictions() {
    await openWindow(context, '/gsw config', 54)
    await until(context, () => /^General\b/i.test(itemText(context.bot.currentWindow?.slots[19])), 'Configuration categories did not load')
    await clickWindowTransition(context, 25)
    context.expect(/^Disabled worlds\b/i.test(itemText(context.bot.currentWindow.slots[0])), 'World restriction category is missing its disabled-worlds setting')
  }

  async function worlds() {
    const source = await readFile(configFile, 'utf8')
    const value = source.match(/^disabled-worlds\s*=\s*(\[[^\]]*\])/m)?.[1]
    context.expect(value !== undefined, 'Saved disabled-worlds setting is not an array')
    return JSON.parse(value)
  }

  async function submitWorlds(value, verifyGuidance = false) {
    const messageStart = context.report.messages.length
    const prompt = context.waitForMessage(/world_nether/i, MODE_TIMEOUT)
    await context.bot.clickWindow(0, 0, 0)
    await until(context, () => context.bot.currentWindow === null, 'Disabled-worlds chat input did not open')
    await prompt
    if (verifyGuidance) {
      const text = context.report.messages.slice(messageStart).map(message => message.message).join('\n')
      context.expect(text.includes('world_nether') && text.includes('world_the_end') && text.includes('[]'),
        'Disabled-worlds prompt is missing world-name or empty-list examples', { text })
      context.expect(!/\b(?:stone|deepslate)\b/i.test(text), 'Disabled-worlds prompt still suggests block materials', { text })
      context.expect(!/<\/?[a-z][^>]*>/i.test(text), 'Disabled-worlds prompt leaks formatting tags', { text })
      context.report.configInputGuidance = text
    }
    const reopened = context.waitForEvent('windowOpen', () => true, MODE_TIMEOUT)
    context.bot.chat(value)
    await reopened
    await context.sleep(200)
  }

  try {
    await context.command('/gsw language self en_US', /en_US|English/i, MODE_TIMEOUT)
    await setMode(context, 'survival')
    await context.step('commented default configuration loads successfully and explains its settings', async () => {
      context.expect(comments.length > 0, 'Generated config.toml contains no explanatory comments')
      context.expect(comments.join('\n').includes('world_nether') && comments.join('\n').includes('world_the_end'),
        'Generated configuration comments omit disabled-world examples')
      await context.command('/gsw status', /Switching:.*Enabled/i, MODE_TIMEOUT)
      context.expect((await worlds()).length === 0, 'Default world restrictions are not empty')
      context.report.configCommentEvidence = { originalCommentCount: comments.length, originalComments: comments }
    })

    await context.step('Disabled worlds tooltip and chat prompt use world-name examples and empty-list guidance', async () => {
      await restrictions()
      const tooltip = itemText(context.bot.currentWindow.slots[0])
      context.expect(tooltip.includes('world_nether') && tooltip.includes('world_the_end') && tooltip.includes('[]'),
        'Disabled-worlds tooltip is missing world-name or empty-list examples', { tooltip })
      context.expect(!/\b(?:stone|deepslate)\b/i.test(tooltip), 'Disabled-worlds tooltip still suggests block materials', { tooltip })
      captureWindow(context, 'disabled-worlds-guidance')
      await submitWorlds('cancel', true)
      context.expect(await readFile(configFile, 'utf8') === originalConfig, 'Cancel changed the commented default configuration')
    })

    await context.step('saving the current world in the editor immediately blocks game-mode changes', async () => {
      await submitWorlds(JSON.stringify([worldName]))
      context.expect(JSON.stringify(await worlds()) === JSON.stringify([worldName]), 'World restriction was not saved')
      captureWindow(context, 'disabled-worlds-saved')
      await closeWindow(context)
      await context.command('/gsw set creative', /unavailable in.*world/i, MODE_TIMEOUT)
      context.expect(context.bot.game.gameMode === 'survival', 'Editor world restriction did not hot apply')
    })

    await context.step('cancel preserves an existing world restriction and its runtime behavior', async () => {
      const savedConfig = await readFile(configFile, 'utf8')
      await restrictions()
      await submitWorlds('cancel')
      context.expect(await readFile(configFile, 'utf8') === savedConfig, 'Cancel rewrote the saved configuration')
      await closeWindow(context)
      await context.command('/gsw set creative', /unavailable in.*world/i, MODE_TIMEOUT)
      context.expect(context.bot.game.gameMode === 'survival', 'Cancel removed the active world restriction')
    })

    await context.step('saving an empty list clears restrictions and allows switching without a reload', async () => {
      await restrictions()
      await submitWorlds('[]')
      context.expect((await worlds()).length === 0, 'Empty-list input did not clear world restrictions')
      captureWindow(context, 'disabled-worlds-cleared')
      await closeWindow(context)
      await setMode(context, 'creative')
    })

    await context.step('configuration editor saves preserve every explanatory comment', async () => {
      const savedConfig = await readFile(configFile, 'utf8')
      const savedComments = savedConfig.split(/\r?\n/).filter(line => /^\s*#/.test(line))
      context.expect(JSON.stringify(savedComments) === JSON.stringify(comments), 'Editor saves changed or removed configuration comments', { original: comments, saved: savedComments })
      context.report.configCommentEvidence.savedCommentCount = savedComments.length
      context.report.configCommentEvidence.preserved = true
      await context.command('/gsw status', /Switching:.*Enabled/i, MODE_TIMEOUT)
    })
  } finally {
    await closeWindow(context)
    await applyFile(context, configFile, originalConfig)
    context.bot.clearControlStates()
  }
}

function captureWindow(context, name) {
  const window = context.bot.currentWindow
  const title = componentText(window.title)
  context.expect(!/<\/?[a-z][^>]*>/i.test(title), `${name} title contains a literal formatting tag`, { title })
  for (let slot = 0; slot < window.inventoryStart; slot++) {
    const text = itemText(window.slots[slot])
    context.expect(!/<\/?[a-z][^>]*>/i.test(text), `${name} slot ${slot} contains a literal formatting tag`, { text })
  }
  context.report.windows ??= []
  context.report.windows.push({
    name,
    title: window.title,
    items: window.slots.slice(0, window.inventoryStart).map(describeItem)
  })
}

async function permissionChecks(context) {
  await context.step('ordinary player can read help and select a personal language', async () => {
    await context.command('/gsw', /\(\(\(/, MODE_TIMEOUT)
    await context.command('/gsw language self en_US', /en_US|English/i, MODE_TIMEOUT)
  })
  for (const command of ['/gsw set creative', '/gsw menu', '/gsw config', '/gsw debug dump upload=false']) {
    await context.step(`ordinary player is denied ${command}`, async () => {
      await context.command(command, /permission|not permitted|not allowed/i, MODE_TIMEOUT)
      context.expect(context.bot.game.gameMode === 'survival', 'Denied command changed game mode')
      context.expect(context.bot.currentWindow === null, 'Denied command opened an inventory')
    })
  }
  await context.step('ordinary offhand swaps do not grant a game mode', async () => {
    await doubleSwap(context)
    await context.sleep(700)
    context.expect(context.bot.game.gameMode === 'survival', 'Unprivileged gesture changed game mode')
  })
}

async function persistedChecks(context) {
  await context.step('personal gesture preference survives a server restart', async () => {
    await context.command('/gsw status', /Gestures:.*Disabled/i, MODE_TIMEOUT)
    await setMode(context, 'survival')
    await context.sleep(COOLDOWN)
    await doubleSwap(context)
    await context.sleep(700)
    context.expect(context.bot.game.gameMode === 'survival', 'Disabled gesture preference was not restored')
    await context.command('/gsw toggle enabled=true', /gestures enabled/i, MODE_TIMEOUT)
  })
}

async function fileChecks(context) {
  const directory = process.env.GSW_QA_PLUGIN_DIR
  context.expect(directory !== undefined, 'Set GSW_QA_PLUGIN_DIR to this isolated instance plugin data folder')
  const configFile = path.join(directory, 'config.toml')
  const germanFile = path.join(directory, 'languages', 'de_DE.toml')
  const frenchFile = path.join(directory, 'languages', 'fr_FR.toml')
  const englishFile = path.join(directory, 'languages', 'en_US.toml')
  const originalConfig = await readFile(configFile, 'utf8')
  const originalEnglish = await readFile(englishFile, 'utf8')
  const originalGerman = await readFile(germanFile, 'utf8').catch(error => {
    if (error.code !== 'ENOENT') throw error
    return null
  })
  const originalFrench = await readFile(frenchFile, 'utf8').catch(error => {
    if (error.code !== 'ENOENT') throw error
    return null
  })
  try {
    await context.command('/gsw language self en_US', /en_US|English/i, MODE_TIMEOUT)
    await context.command('/gsw toggle enabled=true', /gestures enabled/i, MODE_TIMEOUT)
    await setMode(context, 'survival')
    await context.step('hot reload applies external configuration edits', async () => {
      await applyFile(context, configFile, originalConfig.replace(/^enabled\s*=\s*true/m, 'enabled = false'))
      await context.command('/gsw status', /Switching:.*Disabled/i, MODE_TIMEOUT)
      await context.command('/gsw set creative', /switching is disabled/i, MODE_TIMEOUT)
      context.expect(context.bot.game.gameMode === 'survival', 'Disabled configuration permitted a mode change')
    })
    await context.step('invalid configuration preserves the previous runtime settings', async () => {
      await applyFile(context, configFile, '[general]\nenabled = "invalid"\n')
      await context.command('/gsw status', /Switching:.*Disabled/i, MODE_TIMEOUT)
      await applyFile(context, configFile, originalConfig)
      await context.command('/gsw status', /Switching:.*Enabled/i, MODE_TIMEOUT)
    })
    await context.step('disabled worlds reject explicit and gesture changes', async () => {
      const world = 'world'
      context.expect(originalConfig.includes('disabled-worlds = []'), 'Expected the default empty disabled-world list')
      await applyFile(context, configFile, originalConfig.replace('disabled-worlds = []', `disabled-worlds = ["${world}"]`))
      await context.command('/gsw set creative', /unavailable in.*world/i, MODE_TIMEOUT)
      await doubleSwap(context)
      await context.sleep(700)
      context.expect(context.bot.game.gameMode === 'survival', 'Disabled-world gesture changed mode')
      await applyFile(context, configFile, originalConfig)
    })
    await context.step('empty-hand restriction blocks held-item gestures', async () => {
      await applyFile(context, configFile, originalConfig.replace('require-empty-hands = false', 'require-empty-hands = true'))
      await context.command('/give @s stone 1', /given|gave/i, MODE_TIMEOUT)
      await until(context, () => context.bot.inventory.items().some(item => item.name === 'stone'), 'Test stone was not received')
      await doubleSwap(context)
      await context.sleep(700)
      context.expect(context.bot.game.gameMode === 'survival', 'Held item bypassed the empty-hand restriction')
      await context.command('/clear', /removed/i, MODE_TIMEOUT)
      await applyFile(context, configFile, originalConfig)
    })
    await context.step('external English translation edits apply automatically', async () => {
      const editedEnglish = originalEnglish.replace(/^(\[mode\]\r?\n)([^[]*)/m, (section, header, content) =>
        header + content.replace(/^creative\s*=.*$/m, 'creative = "Creative File QA"'))
      context.expect(editedEnglish !== originalEnglish, 'Could not update the English mode.creative entry')
      await applyFile(context, englishFile, editedEnglish)
      await context.command('/gsw set creative', /Creative File QA/i, MODE_TIMEOUT)
      await applyFile(context, englishFile, originalEnglish)
      await setMode(context, 'survival')
    })
    await context.step('missing personal translation falls back to English with a different server language', async () => {
      await writeFile(germanFile, '[mode]\ncreative = "Kreativ"\n')
      await writeFile(frenchFile, '[switch]\nchanged = "{prefix}French server message: {mode}"\n')
      await applyFile(context, configFile, originalConfig.replace('language = "en_US"', 'language = "fr_FR"'))
      await context.command('/gsw language self de_DE', /de_DE|Deutsch/i, MODE_TIMEOUT)
      await context.sleep(COOLDOWN)
      await context.command('/gsw set creative', /Game mode changed to/i, MODE_TIMEOUT)
      await until(context, () => context.bot.game.gameMode === 'creative', 'English fallback interrupted the mode change')
      await context.command('/gsw language self en_US', /en_US|English/i, MODE_TIMEOUT)
    })
  } finally {
    await context.command('/gsw language self en_US', /en_US|English/i, MODE_TIMEOUT)
    await writeFile(englishFile, originalEnglish)
    if (originalGerman === null) await rm(germanFile, { force: true })
    else await writeFile(germanFile, originalGerman)
    if (originalFrench === null) await rm(frenchFile, { force: true })
    else await writeFile(frenchFile, originalFrench)
    await applyFile(context, configFile, originalConfig)
  }
}

async function startupChecks(context) {
  const directory = process.env.GSW_QA_PLUGIN_DIR
  context.expect(directory !== undefined, 'Set GSW_QA_PLUGIN_DIR to this isolated instance plugin data folder')
  await context.step('first startup installs only the English catalogue with formatting documentation', async () => {
    const catalogues = (await readdir(path.join(directory, 'languages'))).filter(file => file.endsWith('.toml')).sort()
    context.expect(JSON.stringify(catalogues) === JSON.stringify(['en_US.toml']), 'Startup installed unexpected language catalogues', { catalogues })
    const english = await readFile(path.join(directory, 'languages', 'en_US.toml'), 'utf8')
    const header = english.slice(0, english.search(/^\s*\[/m))
    context.expect(header.includes('{prefix}') && /MiniMessage|gradient/i.test(header), 'English catalogue is missing placeholder or formatting documentation')
    context.report.languageHeader = header
  })
  await context.step('diagnostic uploads and metrics have enabled defaults', async () => {
    const config = await readFile(path.join(directory, 'config.toml'), 'utf8')
    context.expect(/upload-enabled\s*=\s*true/.test(config), 'Diagnostic uploads default is not enabled')
    context.expect(/\[metrics\][\s\S]*?enabled\s*=\s*true/.test(config), 'Metrics default is not enabled')
    const metrics = await readFile(path.join(directory, '..', 'bStats', 'config.yml'), 'utf8')
    context.expect(/enabled:\s*true/.test(metrics), 'Shared bStats configuration was not initialized enabled')
  })
  await context.step('Director exposes the current command set', async () => {
    const completions = await context.bot.tabComplete('/gsw ')
    const commands = completions.map(completion => typeof completion === 'string' ? completion : completion.match)
    context.report.commandCompletions = commands
    for (const expected of ['config', 'debug', 'language', 'menu', 'set', 'status', 'toggle']) {
      context.expect(commands.some(command => command === expected || command.endsWith(` ${expected}`)), `Command completion missing ${expected}`, { commands })
    }
    context.expect(!commands.some(command => /\breload\b/.test(command)), 'Command completion still advertises reload', { commands })
  })
}

async function captureMetrics(context, directory, expected) {
  const debugDirectory = path.join(directory, 'debug')
  const previous = new Set(await readdir(debugDirectory).catch(error => {
    if (error.code !== 'ENOENT') throw error
    return []
  }))
  await context.command('/gsw debug dump upload=false', /saved|created|written|\.zip|\.json/i, 20000)
  let dump
  await until(context, async () => {
    dump = (await readdir(debugDirectory)).find(file => file.endsWith('.txt') && !previous.has(file))
    return dump !== undefined
  }, 'Metrics diagnostic dump was not created', 10000)
  const text = await readFile(path.join(debugDirectory, dump), 'utf8')
  for (const [key, value] of Object.entries(expected)) {
    context.expect(text.includes(`${key}: ${value}`), `Unexpected metrics state for ${key}`, { expected: value })
  }
  context.report.metricsSnapshots ??= []
  context.report.metricsSnapshots.push({ file: dump, expected })
}

async function metricsChecks(context) {
  const directory = process.env.GSW_QA_PLUGIN_DIR
  context.expect(directory !== undefined, 'Set GSW_QA_PLUGIN_DIR to this isolated instance plugin data folder')
  const pluginConfig = path.join(directory, 'config.toml')
  const sharedConfig = path.join(directory, '..', 'bStats', 'config.yml')
  const originalPlugin = await readFile(pluginConfig, 'utf8')
  const originalShared = await readFile(sharedConfig, 'utf8')
  try {
    await context.step('bStats is initialized and reporting is enabled', async () => {
      await captureMetrics(context, directory, {
        'bStats enabled': true, 'bStats initialized': true, 'bStats reporting enabled': true
      })
    })
    await context.step('plugin metrics setting stops and restarts reporting automatically', async () => {
      const disabled = originalPlugin.replace(/(\[metrics\]\r?\n)enabled\s*=\s*true/, '$1enabled = false')
      context.expect(disabled !== originalPlugin, 'Could not disable plugin metrics')
      await applyFile(context, pluginConfig, disabled)
      await captureMetrics(context, directory, {
        'bStats enabled': false, 'bStats initialized': false, 'bStats reporting enabled': false
      })
      await applyFile(context, pluginConfig, originalPlugin)
      await captureMetrics(context, directory, {
        'bStats enabled': true, 'bStats initialized': true, 'bStats reporting enabled': true
      })
    })
    await context.step('shared bStats opt-out hot loads while plugin configuration is invalid', async () => {
      await applyFile(context, pluginConfig, '[general]\nenabled = "invalid"\n')
      const disabled = originalShared.replace(/^enabled:\s*true/m, 'enabled: false')
      context.expect(disabled !== originalShared, 'Could not disable shared bStats reporting')
      await applyFile(context, sharedConfig, disabled)
      await captureMetrics(context, directory, {
        'bStats enabled': true, 'bStats initialized': true, 'bStats reporting enabled': false
      })
      await applyFile(context, sharedConfig, originalShared)
      await captureMetrics(context, directory, {
        'bStats enabled': true, 'bStats initialized': true, 'bStats reporting enabled': true
      })
    })
  } finally {
    await writeFile(pluginConfig, originalPlugin)
    await applyFile(context, sharedConfig, originalShared)
  }
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

async function feedbackChecks(context) {
  const directory = process.env.GSW_QA_PLUGIN_DIR
  context.expect(directory !== undefined, 'Set GSW_QA_PLUGIN_DIR to this isolated instance plugin data folder')
  const configFile = path.join(directory, 'config.toml')
  const originalConfig = await readFile(configFile, 'utf8')
  const recorder = feedbackRecorder(context)
  const silent = { 'chat-enabled': false, 'action-bar-enabled': false, 'title-enabled': false, 'sound-enabled': false }
  function values(observation, channel) {
    return observation.packets.filter(packet => packet.channel === channel && (packet.text === undefined || packet.text.length > 0))
  }
  function sound(observation, key, volume, pitch) {
    const matches = values(observation, 'sound').filter(packet => packet.key === key)
    context.expect(matches.length === 1, `Expected one ${key} confirmation sound`, { matches })
    context.expect(matches[0].category === 'player', 'Confirmation sound must use the PLAYERS category', matches[0])
    context.expect(Math.abs(matches[0].volume - volume) < 0.001 && Math.abs(matches[0].pitch - pitch) < 0.001,
      'Confirmation sound ignored configured volume or pitch', matches[0])
  }
  function noSound(observation) {
    context.expect(!values(observation, 'sound').some(packet => ['minecraft:ui.button.click', 'minecraft:block.note_block.pling'].includes(packet.key)),
      'Unexpected confirmation sound', observation)
  }
  function noSuccess(observation) {
    context.expect(values(observation, 'chat').length === 0, 'Disabled success chat sent a message', observation)
    context.expect(values(observation, 'title').length === 0 && values(observation, 'subtitle').length === 0, 'Unexpected success title', observation)
    context.expect(observation.windows.length === 0, 'Unexpected success menu popup', observation)
    noSound(observation)
  }
  async function configure(settings) {
    let changed = false
    const content = originalConfig.replace(/(^\[feedback\]\r?\n)([\s\S]*?)(?=^\[|$(?![\s\S]))/m, (section, header, body) => {
      for (const [key, value] of Object.entries(settings)) {
        const pattern = new RegExp(`^${key}\\s*=.*$`, 'm')
        context.expect(pattern.test(body), `Missing feedback setting ${key}`)
        body = body.replace(pattern, `${key} = ${JSON.stringify(value)}`)
      }
      changed = true
      return header + body
    })
    context.expect(changed, 'Missing feedback configuration section')
    await applyFile(context, configFile, content)
  }
  async function change(mode, name) {
    await context.sleep(COOLDOWN)
    const mark = recorder.mark()
    context.bot.chat(`/gsw set ${mode}`)
    await until(context, () => context.bot.game.gameMode === mode, `Feedback test did not enter ${mode}`)
    await context.sleep(350)
    return recorder.capture(name, mark)
  }
  async function failure(mode, pattern, name) {
    await context.sleep(COOLDOWN)
    const before = context.bot.game.gameMode
    const mark = recorder.mark()
    context.bot.chat(`/gsw set ${mode}`)
    await context.sleep(600)
    const observation = recorder.capture(name, mark)
    context.expect(context.bot.game.gameMode === before, 'Rejected transition changed the game mode')
    context.expect(values(observation, 'actionbar').some(packet => pattern.test(packet.text)), 'Failure reason was not sent above the hotbar', observation)
    noSuccess(observation)
  }
  try {
    await configure({ 'chat-enabled': true, 'action-bar-enabled': false, 'title-enabled': false, 'sound-enabled': true })
    await context.command('/gsw language self en_US', /en_US|English/i, MODE_TIMEOUT)
    if (context.bot.game.gameMode !== 'survival') {
      context.bot.chat('/gamemode survival')
      await until(context, () => context.bot.game.gameMode === 'survival', 'Could not prepare the initial Survival mode')
    }
    await context.step('configured chat and sound feedback sends only the enabled channels', async () => {
      const observation = await change('creative', 'default-creative')
      context.expect(values(observation, 'chat').some(packet => /game mode changed to.*Creative/i.test(packet.text)), 'Default success chat is missing')
      sound(observation, 'minecraft:ui.button.click', 0.7, 1.2)
      for (const channel of ['actionbar', 'title', 'subtitle']) context.expect(values(observation, channel).length === 0, `Default ${channel} is unexpectedly enabled`)
      context.expect(observation.windows.length === 0, 'Default menu popup is unexpectedly enabled')
    })
    await context.step('disabled feedback channels leave a Spectator switch silent', async () => {
      await configure(silent)
      const observation = await change('spectator', 'silent-spectator')
      noSuccess(observation)
      context.expect(values(observation, 'actionbar').length === 0, 'Disabled action bar sent Spectator feedback')
    })
    await context.step('title and action bar use the mode label and configured title duration', async () => {
      await configure({ ...silent, 'action-bar-enabled': true, 'title-enabled': true, 'popup-duration-ticks': 35 })
      const observation = await change('survival', 'overlay-survival')
      context.expect(values(observation, 'title').some(packet => /Survival/.test(packet.text)), 'Title is missing the selected mode')
      context.expect(values(observation, 'subtitle').some(packet => /Game mode changed/i.test(packet.text)), 'Normal subtitle is missing')
      context.expect(values(observation, 'actionbar').some(packet => /Game mode:.*Survival/i.test(packet.text)), 'Action bar is missing the selected mode')
      context.expect(values(observation, 'title-time').some(packet => packet.stay === 35), 'Title did not use the configured 35-tick stay duration', observation)
      context.expect(values(observation, 'chat').length === 0, 'Overlay-only switch wrote to chat')
      noSound(observation)
    })
    await context.step('Spectator overlays include the exit gesture without writing to chat', async () => {
      await context.sleep(2200)
      const observation = await change('spectator', 'overlay-spectator')
      context.expect(values(observation, 'title').some(packet => /Spectator/.test(packet.text)), 'Spectator title is missing')
      for (const channel of ['actionbar', 'subtitle']) {
        context.expect(values(observation, channel).some(packet => /Sneak three times.*Spectator/i.test(packet.text)), `Spectator ${channel} omitted its exit hint`, observation)
      }
      context.expect(values(observation, 'chat').length === 0, 'Spectator hint bypassed disabled chat')
      noSound(observation)
    })
    await context.step('integer sound values accept fractional GUI steps and exact decimal chat input', async () => {
      await configure({ ...silent, 'sound-enabled': true, sound: 'minecraft:block.note_block.pling', 'sound-volume': 1, 'sound-pitch': 1 })
      await openWindow(context, '/gsw config', 54)
      await until(context, () => /^General\b/i.test(itemText(context.bot.currentWindow?.slots[19])), 'Configuration did not load')
      await clickWindowTransition(context, 29)
      await clickWindowTransition(context, 6, 1)
      await submitNamedSetting(context, /^Sound pitch\b/i, '1.3')
      const config = await readFile(configFile, 'utf8')
      context.expect(/^sound-volume\s*=\s*0\.9\s*$/m.test(config), 'Integer volume did not decrement by the fractional 0.1 step')
      context.expect(/^sound-pitch\s*=\s*1\.3\s*$/m.test(config), 'Integer pitch did not accept exact decimal chat input')
      captureWindow(context, 'feedback-fractional-sound-settings')
      await closeWindow(context)
      const observation = await change('survival', 'fractional-sound-survival')
      sound(observation, 'minecraft:block.note_block.pling', 0.9, 1.3)
    })
    await context.step('configured sound changes hot apply with exact key volume pitch and category', async () => {
      await configure({ ...silent, 'sound-enabled': true, sound: 'minecraft:block.note_block.pling', 'sound-volume': 0.25, 'sound-pitch': 1.7 })
      const observation = await change('creative', 'custom-sound-creative')
      sound(observation, 'minecraft:block.note_block.pling', 0.25, 1.7)
      context.expect(!values(observation, 'sound').some(packet => packet.key === 'minecraft:ui.button.click'), 'Previous sound still played after hot apply')
      context.expect(values(observation, 'chat').length === 0, 'Sound-only feedback wrote to chat')
    })
    await context.step('a no-op uses failure feedback without a success sound title or menu', async () => {
      await configure({ ...silent, 'action-bar-enabled': true, 'title-enabled': true, 'sound-enabled': true })
      await failure('creative', /already.*Creative/i, 'noop-creative')
    })
    await context.step('a cancelled Bukkit mode-change event emits no success feedback', async () => {
      await failure('adventure', /prevented.*game mode change/i, 'cancelled-adventure')
    })
    await context.step('blocked switching still explains the failure when every success channel is disabled', async () => {
      await configure(silent)
      const configured = await readFile(configFile, 'utf8')
      await applyFile(context, configFile, configured.replace(/(^\[general\]\r?\n[\s\S]*?^enabled\s*=\s*)true/m, '$1false'))
      await failure('survival', /switching is disabled/i, 'globally-disabled')
      await configure(silent)
    })
    await context.step('the explicit menu command opens exactly one selector after a mode switch', async () => {
      const switched = await change('survival', 'switch-without-selector')
      context.expect(switched.windows.length === 0, 'Successful switch opened an inventory', switched)
      const mark = recorder.mark()
      await openWindow(context, '/gsw menu', 54)
      const observation = recorder.capture('manual-selector', mark)
      context.expect(observation.windows.length === 1 && observation.windows[0].slots === 54, 'Menu command did not open exactly one selector', observation)
      context.expect(/selected/i.test(itemText(context.bot.currentWindow.slots[19])), 'Manual selector did not mark the new mode')
      captureWindow(context, 'feedback-manual-selector')
    })
    await context.step('selecting a mode inside the selector produces one refresh without a second popup', async () => {
      await context.sleep(COOLDOWN)
      const mark = recorder.mark()
      await clickWindowTransition(context, 21)
      await until(context, () => context.bot.game.gameMode === 'creative', 'Selector did not select Creative')
      await context.sleep(350)
      const observation = recorder.capture('selector-refresh', mark)
      context.expect(observation.windows.length === 1, 'Selector click opened duplicate inventories', observation)
      context.expect(/selected/i.test(itemText(context.bot.currentWindow.slots[21])), 'Selector refresh did not mark Creative')
      await closeWindow(context)
    })
    await context.step('a mode-change listener can replace the selector without its inventory being overwritten', async () => {
      await openWindow(context, '/gsw menu', 54)
      await context.sleep(COOLDOWN)
      const mark = recorder.mark()
      const before = context.bot.game.gameMode
      await clickWindowTransition(context, 23)
      await context.sleep(350)
      const observation = recorder.capture('event-owned-inventory', mark)
      context.expect(context.bot.game.gameMode === before, 'Cancelled selector transition changed the mode')
      context.expect(observation.windows.length === 1 && observation.windows[0].slots === 27, 'Selector replaced the event-owned inventory', observation)
      context.expect(componentText(context.bot.currentWindow?.title) === 'Feedback event inventory', 'Event-owned inventory did not remain open')
      captureWindow(context, 'feedback-event-owned-inventory')
      noSound(observation)
      await closeWindow(context)
    })
    await context.step('a mode switch preserves an open configuration editor', async () => {
      await openWindow(context, '/gsw config', 54)
      await until(context, () => /^General\b/i.test(itemText(context.bot.currentWindow?.slots[19])), 'Configuration did not load')
      const id = context.bot.currentWindow.id
      const observation = await change('survival', 'preserved-config')
      context.expect(observation.windows.length === 0 && context.bot.currentWindow?.id === id, 'Mode popup displaced the configuration editor', observation)
      captureWindow(context, 'feedback-preserved-config')
      await closeWindow(context)
    })
    await context.step('a mode switch preserves an open language editor', async () => {
      await openWindow(context, '/gsw language server edit en_US', 54)
      await until(context, () => context.bot.currentWindow?.slots[49]?.name === 'compass', 'Language catalogue did not load')
      const id = context.bot.currentWindow.id
      const observation = await change('creative', 'preserved-language')
      context.expect(observation.windows.length === 0 && context.bot.currentWindow?.id === id, 'Mode popup displaced the language editor', observation)
      captureWindow(context, 'feedback-preserved-language')
      await closeWindow(context)
    })
    await context.step('a mode switch preserves an open chest', async () => {
      const position = context.bot.entity.position.floored().offset(1, 0, 0)
      if (context.bot.blockAt(position)?.name !== 'chest') context.bot.chat(`/setblock ${position.x} ${position.y} ${position.z} chest`)
      await until(context, () => context.bot.blockAt(position)?.name === 'chest', 'Test chest did not appear')
      const opened = context.waitForEvent('windowOpen', () => true, MODE_TIMEOUT)
      await context.bot.activateBlock(context.bot.blockAt(position))
      await opened
      context.expect(context.bot.currentWindow.inventoryStart === 27, 'Test container is not a single chest')
      const id = context.bot.currentWindow.id
      const observation = await change('survival', 'preserved-chest')
      context.expect(observation.windows.length === 0 && context.bot.currentWindow?.id === id, 'Mode popup displaced the chest', observation)
      await closeWindow(context)
    })
    await context.step('feedback configuration exposes friendly controls with clean rendered metadata', async () => {
      await openWindow(context, '/gsw config', 54)
      await until(context, () => /^General\b/i.test(itemText(context.bot.currentWindow?.slots[19])), 'Configuration did not load')
      await clickWindowTransition(context, 29)
      const entries = context.bot.currentWindow.slots.slice(0, 45).filter(item => item && item.name !== 'black_stained_glass_pane').map(itemText)
      const labels = ['Chat messages', 'Action bar popup', 'Title popup', 'Popup duration', 'Switch sound', 'Sound key', 'Sound volume', 'Sound pitch', 'Fly on entering Creative']
      context.expect(entries.length === labels.length, 'Feedback editor should expose nine settings', { entries })
      for (const [slot, label] of labels.entries()) context.expect(entries[slot].startsWith(label), `Feedback slot ${slot} should expose ${label}`, { entries })
      captureWindow(context, 'feedback-settings')
      await closeWindow(context)
    })
    await context.step('hot disabling active feedback clears its title and action bar before their duration expires', async () => {
      await configure({ ...silent, 'title-enabled': true, 'action-bar-enabled': true, 'popup-duration-ticks': 200 })
      const visible = await change('creative', 'long-lived-overlays')
      context.expect(values(visible, 'title').some(packet => /Creative/.test(packet.text)), 'Long-lived title did not appear')
      context.expect(values(visible, 'actionbar').some(packet => /Creative/.test(packet.text)), 'Long-lived action bar did not appear')
      const mark = recorder.mark()
      await configure(silent)
      const cleared = recorder.capture('hot-disabled-active-overlays', mark)
      context.expect(values(cleared, 'title-clear').length > 0, 'Hot apply did not clear the active title')
      context.expect(cleared.packets.some(packet => packet.channel === 'actionbar' && packet.text.trim().length === 0), 'Hot apply did not clear the active action bar')
    })
    await context.step('disabling previously enabled popups and sound hot applies without stale feedback', async () => {
      await configure(silent)
      const observation = await change('survival', 'restored-silence')
      noSuccess(observation)
      context.expect(values(observation, 'actionbar').length === 0, 'Disabled action bar still sent feedback')
    })
  } finally {
    await closeWindow(context)
    await applyFile(context, configFile, originalConfig)
    context.bot.clearControlStates()
    recorder.stop()
  }
}

function feedbackValues(source) {
  const section = source.match(/^\[feedback\]\r?\n([\s\S]*?)(?=^\[|$(?![\s\S]))/m)?.[1] ?? ''
  return Object.fromEntries(section.split(/\r?\n/).filter(line => /^[a-z][a-z-]*\s*=/.test(line)).map(line => {
    const separator = line.indexOf('=')
    return [line.slice(0, separator).trim(), line.slice(separator + 1).trim()]
  }))
}

async function popupDefaultChecks(context) {
  const directory = process.env.GSW_QA_PLUGIN_DIR
  context.expect(directory !== undefined, 'Set GSW_QA_PLUGIN_DIR to this isolated instance plugin data folder')
  const configFile = path.join(directory, 'config.toml')
  const originalConfig = await readFile(configFile, 'utf8')
  const recorder = feedbackRecorder(context)
  function assertHud(observation, mode, title = true, actionbar = true) {
    const titles = observation.packets.filter(packet => packet.channel === 'title' && packet.text.trim().length > 0)
    const bars = observation.packets.filter(packet => packet.channel === 'actionbar' && packet.text.trim().length > 0)
    const pattern = new RegExp(mode, 'i')
    context.expect(title ? titles.some(packet => pattern.test(packet.text)) : titles.length === 0,
      `Unexpected title feedback for ${mode}`, observation)
    context.expect(actionbar ? bars.some(packet => pattern.test(packet.text)) : bars.length === 0,
      `Unexpected action bar feedback for ${mode}`, observation)
    context.expect(observation.windows.length === 0, 'A mode switch opened an inventory', observation)
  }
  async function change(mode, name, title = true, actionbar = true) {
    await context.sleep(COOLDOWN)
    const mark = recorder.mark()
    context.bot.chat(`/gsw set ${mode}`)
    await until(context, () => context.bot.game.gameMode === mode, `Popup test did not enter ${mode}`)
    await context.sleep(350)
    const observation = recorder.capture(name, mark)
    assertHud(observation, mode, title, actionbar)
    return observation
  }
  async function openFeedback() {
    await openWindow(context, '/gsw config', 54)
    await until(context, () => /^General\b/i.test(itemText(context.bot.currentWindow?.slots[19])), 'Configuration did not load')
    await clickWindowTransition(context, 29)
  }
  async function toggle(slot, key, expected) {
    const before = feedbackValues(await readFile(configFile, 'utf8'))
    await openFeedback()
    await clickWindowTransition(context, slot)
    const after = feedbackValues(await readFile(configFile, 'utf8'))
    context.expect(after[key] === String(expected), `${key} did not save ${expected}`, { before, after })
    for (const [name, value] of Object.entries(before)) {
      if (name !== key) context.expect(after[name] === value, `Toggling ${key} also changed ${name}`, { before, after })
    }
    context.expect(Object.keys(after).length === Object.keys(before).length, 'A feedback toggle changed the configuration shape')
    captureWindow(context, `popup-defaults-${key}-${expected}`)
    await closeWindow(context)
  }
  try {
    await context.command('/gsw language self en_US', /en_US|English/i, MODE_TIMEOUT)
    await context.command('/gsw toggle enabled=true', /gestures enabled/i, MODE_TIMEOUT)
    if (context.bot.game.gameMode !== 'survival') {
      context.bot.chat('/gamemode survival')
      await until(context, () => context.bot.game.gameMode === 'survival', 'Could not prepare the initial Survival mode')
    }
    await context.step('fresh feedback defaults enable both HUD channels and expose the nine current controls', async () => {
      const settings = feedbackValues(originalConfig)
      context.expect(settings['title-enabled'] === 'true' && settings['action-bar-enabled'] === 'true', 'Fresh defaults do not enable both HUD channels', { settings })
      context.expect(settings['chat-enabled'] === 'true' && settings['sound-enabled'] === 'true', 'Fresh chat or sound confirmation default changed', { settings })
      await openFeedback()
      const entries = context.bot.currentWindow.slots.slice(0, 45).filter(item => item && item.name !== 'black_stained_glass_pane').map(itemText)
      const labels = ['Chat messages', 'Action bar popup', 'Title popup', 'Popup duration', 'Switch sound', 'Sound key', 'Sound volume', 'Sound pitch', 'Fly on entering Creative']
      context.expect(entries.length === labels.length, 'Feedback editor should expose nine settings', { entries })
      for (const [slot, label] of labels.entries()) context.expect(entries[slot].startsWith(label), `Feedback slot ${slot} should expose ${label}`, { entries })
      captureWindow(context, 'popup-defaults-feedback-controls')
      context.report.feedbackDefaults = settings
      await closeWindow(context)
    })
    await context.step('a command switch shows its default title and action bar without opening an inventory', async () => {
      await change('creative', 'default-command-creative')
      context.expect(context.bot.currentWindow === null, 'Command opened the selector')
    })
    await context.step('the ordinary double-swap gesture shows both HUD channels without opening an inventory', async () => {
      await context.sleep(COOLDOWN)
      const mark = recorder.mark()
      await doubleSwap(context)
      await until(context, () => context.bot.game.gameMode === 'survival', 'Ordinary double-swap gesture did not select Survival')
      await context.sleep(350)
      assertHud(recorder.capture('default-gesture-survival', mark), 'survival')
      context.expect(context.bot.currentWindow === null, 'Ordinary gesture opened the selector')
    })
    await context.step('the sneaking double-swap gesture shows both HUD channels without opening an inventory', async () => {
      await context.sleep(COOLDOWN)
      const mark = recorder.mark()
      context.bot.setControlState('sneak', true)
      await context.sleep(120)
      await doubleSwap(context)
      await until(context, () => context.bot.game.gameMode === 'adventure', 'Sneaking double-swap gesture did not select Adventure')
      context.bot.setControlState('sneak', false)
      await context.sleep(350)
      assertHud(recorder.capture('default-sneaking-gesture-adventure', mark), 'adventure')
      context.expect(context.bot.currentWindow === null, 'Sneaking gesture opened the selector')
    })
    await context.step('the Title control changes only its setting and never opens the selector on a later switch', async () => {
      await toggle(2, 'title-enabled', false)
      await change('creative', 'title-disabled-creative', false, true)
      await toggle(2, 'title-enabled', true)
      await change('survival', 'title-enabled-survival')
      context.expect(context.bot.currentWindow === null, 'Title feedback opened an inventory')
    })
    await context.step('the Action bar control changes only its setting and never opens the selector on a later switch', async () => {
      await toggle(1, 'action-bar-enabled', false)
      await change('creative', 'actionbar-disabled-creative', true, false)
      await toggle(1, 'action-bar-enabled', true)
      await change('survival', 'actionbar-enabled-survival')
      context.expect(context.bot.currentWindow === null, 'Action bar feedback opened an inventory')
    })
    await context.step('the manual menu command still opens the selector and mode selection refreshes it once', async () => {
      await openWindow(context, '/gsw menu', 54)
      captureWindow(context, 'popup-defaults-manual-selector')
      context.expect(/selected/i.test(itemText(context.bot.currentWindow.slots[19])), 'Manual selector did not mark Survival')
      await context.sleep(COOLDOWN)
      const mark = recorder.mark()
      await clickWindowTransition(context, 21)
      await until(context, () => context.bot.game.gameMode === 'creative', 'Manual selector did not select Creative')
      await context.sleep(350)
      const observation = recorder.capture('manual-selector-creative', mark)
      context.expect(observation.windows.length === 1 && observation.windows[0].slots === 54, 'Manual selection did not produce exactly one selector refresh', observation)
      context.expect(/selected/i.test(itemText(context.bot.currentWindow.slots[21])), 'Manual selector did not mark Creative')
      context.expect(observation.packets.some(packet => packet.channel === 'title' && /Creative/.test(packet.text)), 'Manual selector switch did not show its title')
      context.expect(observation.packets.some(packet => packet.channel === 'actionbar' && /Creative/.test(packet.text)), 'Manual selector switch did not show its action bar')
      await closeWindow(context)
    })
    await context.step('HUD feedback preserves an already-open configuration editor during a command switch', async () => {
      await openFeedback()
      const id = context.bot.currentWindow.id
      await change('survival', 'hud-preserves-configuration')
      context.expect(context.bot.currentWindow?.id === id, 'HUD feedback replaced the configuration editor')
      context.expect(/^Title popup/.test(itemText(context.bot.currentWindow.slots[2])), 'Configuration controls changed during the switch')
      captureWindow(context, 'popup-defaults-preserved-configuration')
      await closeWindow(context)
    })
  } finally {
    await closeWindow(context)
    await applyFile(context, configFile, originalConfig)
    context.bot.clearControlStates()
    recorder.stop()
  }
}

export default {
  name: 'gamemode-switcher',
  description: 'Verify commands, game mode gestures, personal preferences, inventory menus, automatic configuration and translation updates, and local diagnostics.',
  async run(context) {
    if (context.options.command === 'permissions') return permissionChecks(context)
    if (context.options.command === 'persisted') return persistedChecks(context)
    if (context.options.command === 'files') return fileChecks(context)
    if (context.options.command === 'startup') return startupChecks(context)
    if (context.options.command === 'metrics') return metricsChecks(context)
    if (context.options.command === 'switch-feedback') return feedbackChecks(context)
    if (context.options.command === 'popup-defaults') return popupDefaultChecks(context)
    if (context.options.command === 'menu-alignment') return menuChecks(context)
    if (context.options.command === 'menu-limited') return limitedMenuChecks(context)
    if (context.options.command === 'menu-revoked') return revokedMenuChecks(context)
    if (context.options.command === 'config-help') {
      await configHelpChecks(context)
      return menuChecks(context)
    }
    if (context.options.command === 'corrections') await startupChecks(context)

    try {
      await context.step('command help and status are available', async () => {
        await context.command('/gsw', /\(\(\(/, MODE_TIMEOUT)
        await context.command('/gsw status', /Java 17.*1\.20\.1/i, MODE_TIMEOUT)
        await context.command('/gsw toggle enabled=true', /gestures enabled/i, MODE_TIMEOUT)
        await context.command('/clear', /removed|no items/i, MODE_TIMEOUT)
      })

      await context.step('explicit commands select all four game modes', async () => {
        for (const mode of ['creative', 'adventure', 'spectator', 'survival']) await setMode(context, mode)
      })

      await context.step('double offhand swap toggles survival and creative', async () => {
        await context.sleep(COOLDOWN)
        await doubleSwap(context)
        await until(context, () => context.bot.game.gameMode === 'creative', 'Double swap did not select creative')
        await context.sleep(COOLDOWN)
        await doubleSwap(context)
        await until(context, () => context.bot.game.gameMode === 'survival', 'Double swap did not return to survival')
      })

      await context.step('slow offhand swaps do not count as a double tap', async () => {
        await context.sleep(COOLDOWN)
        swap(context)
        await context.sleep(750)
        swap(context)
        await context.sleep(700)
        context.expect(context.bot.game.gameMode === 'survival', 'Expired tap window still changed game mode')
      })

      await context.step('sneaking double swap toggles survival and adventure', async () => {
        context.bot.setControlState('sneak', true)
        await context.sleep(120)
        await doubleSwap(context)
        await until(context, () => context.bot.game.gameMode === 'adventure', 'Sneaking double swap did not select adventure')
        await context.sleep(COOLDOWN)
        await doubleSwap(context)
        await until(context, () => context.bot.game.gameMode === 'survival', 'Sneaking double swap did not return to survival')
        context.bot.setControlState('sneak', false)
      })

      await context.step('spectator exit counts three separate sneak presses', async () => {
        await setMode(context, 'creative')
        await context.sleep(COOLDOWN)
        context.bot.setControlState('sneak', true)
        await context.sleep(120)
        await doubleSwap(context)
        await until(context, () => context.bot.game.gameMode === 'spectator', 'Sneaking creative double swap did not select spectator')
        context.bot.setControlState('sneak', false)
        await context.sleep(COOLDOWN)
        for (let press = 0; press < 3; press += 1) {
          context.bot.setControlState('sneak', true)
          await context.sleep(100)
          if (press < 2) context.expect(context.bot.game.gameMode === 'spectator', 'Spectator exit counted releases as presses')
          context.bot.setControlState('sneak', false)
          await context.sleep(100)
        }
        await until(context, () => context.bot.game.gameMode === 'creative', 'Triple sneak did not exit spectator into creative')
      })

      await context.step('personal opt-out disables gestures while commands remain available', async () => {
        await setMode(context, 'survival')
        await context.command('/gsw toggle enabled=false', /gestures disabled/i, MODE_TIMEOUT)
        await context.sleep(COOLDOWN)
        await doubleSwap(context)
        await context.sleep(700)
        context.expect(context.bot.game.gameMode === 'survival', 'Opt-out did not suppress gestures')
        await setMode(context, 'creative')
        await context.command('/gsw toggle enabled=true', /gestures enabled/i, MODE_TIMEOUT)
      })

      await context.step('selector inventory chooses a mode and retains its controls', async () => {
        await setMode(context, 'survival')
        await context.sleep(COOLDOWN)
        const window = await openWindow(context, '/gsw menu', 54)
        for (const slot of [13, 19, 21, 23, 25, 28, 30, 32, 34, 53]) {
          context.expect(window.slots[slot] !== null, `Selector slot ${slot} is empty`)
        }
        captureWindow(context, 'game-mode-selector')
        await context.bot.clickWindow(21, 0, 0)
        await until(context, () => context.bot.game.gameMode === 'creative', 'Selector click did not choose creative')
        context.expect(context.bot.inventory.items().length === 0, 'Selector controls leaked into player inventory')
        await closeWindow(context)
      })

      await context.step('configuration editor applies and restores the global enabled setting', async () => {
        await openWindow(context, '/gsw config', 54)
        await until(context, () => /^General\b/i.test(itemText(context.bot.currentWindow?.slots[19])), 'Configuration editor never loaded settings')
        captureWindow(context, 'configuration-editor')
        await clickWindowTransition(context, 19)
        captureWindow(context, 'configuration-general-section')
        await clickWindowTransition(context, 0)
        await closeWindow(context)
        await context.command('/gsw status', /Switching:.*Disabled/i, MODE_TIMEOUT)
        await context.command('/gsw set survival', /switching is disabled/i, MODE_TIMEOUT)
        context.expect(context.bot.game.gameMode === 'creative', 'Global disable did not block the explicit command')
        await openWindow(context, '/gsw config', 54)
        await until(context, () => /^General\b/i.test(itemText(context.bot.currentWindow?.slots[19])), 'Configuration editor never reloaded settings')
        await clickWindowTransition(context, 19)
        await clickWindowTransition(context, 0)
        await clickWindowTransition(context, 49)
        await context.bot.clickWindow(53, 0, 0)
        await until(context, () => context.bot.currentWindow === null, 'Configuration close control failed')
        await context.command('/gsw status', /Switching:.*Enabled/i, MODE_TIMEOUT)
      })

      await context.step('personal language selection can be changed and reset', async () => {
        await context.command('/gsw language self en_US', /en_US|English/i, MODE_TIMEOUT)
        await context.command('/gsw language self reset', /reset|server|default/i, MODE_TIMEOUT)
      })

      await context.step('language editor opens its server translation catalogue', async () => {
        await openWindow(context, '/gsw language server edit en_US', 54)
        await until(context, () => context.bot.currentWindow?.slots.slice(0, 45).some(item => item !== null && item.name !== 'black_stained_glass_pane'), 'Language editor never loaded its catalogue')
        captureWindow(context, 'language-editor')
        await context.bot.clickWindow(53, 0, 0)
        await until(context, () => context.bot.currentWindow === null, 'Language editor close control failed')
      })

      await context.step('language editor saves a translation and restores its original value', async () => {
        await editLanguageEntry(context, 'Creative QA')
        await setMode(context, 'survival')
        await context.sleep(COOLDOWN)
        await context.command('/gsw set creative', /Creative QA/i, MODE_TIMEOUT)
        await until(context, () => context.bot.game.gameMode === 'creative', 'Edited translation interrupted mode switching')
        await editLanguageEntry(context, 'Creative')
      })

      await context.step('debug dump is created without uploading', async () => {
        await context.command('/gsw debug dump upload=false', /saved|created|written|\.zip|\.json/i, 20000)
      })

      await context.step('personal opt-out is saved for the restart scenario', async () => {
        await context.command('/gsw toggle enabled=false', /gestures disabled/i, MODE_TIMEOUT)
        await context.command('/gsw status', /Gestures:.*Disabled/i, MODE_TIMEOUT)
      })
    } finally {
      context.bot.clearControlStates()
      await closeWindow(context)
    }
    if (context.options.command === 'corrections') {
      await fileChecks(context)
      await metricsChecks(context)
    }
  }
}
