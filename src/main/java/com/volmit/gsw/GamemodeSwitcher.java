package com.volmit.gsw;

import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import primal.bukkit.plugin.PrimalPlugin;
import primal.lang.collection.GMap;
import primal.logic.format.F;
import primal.logic.queue.ChronoLatch;
import primal.util.text.C;

public class GamemodeSwitcher extends PrimalPlugin implements Listener
{
	private GMap<Player, ChronoLatch> doubleTaps;

	@Override
	public void start()
	{
		registerListener(this);
		doubleTaps = new GMap<Player, ChronoLatch>();
	}

	@Override
	public void stop()
	{

	}

	@EventHandler
	public void on(PlayerSwapHandItemsEvent e)
	{
		if(!doubleTaps.containsKey(e.getPlayer()))
		{
			doubleTaps.put(e.getPlayer(), new ChronoLatch(600, false));
			return;
		}

		else
		{
			if(!doubleTaps.get(e.getPlayer()).flip())
			{
				doubleTapped(e.getPlayer());
			}

			doubleTaps.remove(e.getPlayer());
		}
	}

	private void doubleTapped(Player p)
	{
		GameMode g = p.getGameMode();
		GameMode out = null;
		switch(g)
		{
			case ADVENTURE:
				out = p.isSneaking() ? GameMode.SURVIVAL : GameMode.CREATIVE;
				break;
			case CREATIVE:
				out = p.isSneaking() ? GameMode.SPECTATOR : GameMode.SURVIVAL;
				break;
			case SPECTATOR:
				out = p.isSneaking() ? GameMode.CREATIVE : GameMode.SURVIVAL;
				break;
			case SURVIVAL:
				out = p.isSneaking() ? GameMode.ADVENTURE : GameMode.CREATIVE;
				break;
			default:
				break;
		}

		p.setGameMode(g);
		p.sendMessage(getTag() + "Switched to " + C.DARK_GREEN + F.capitalize(out.name().toLowerCase()));
		p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_DIAMOND, 1f, 1f);
		p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_DIAMOND, 1f, 0.25f);
		p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_DIAMOND, 1f, 1.75f);
	}

	@Override
	public String getTag(String subTag)
	{
		if(subTag == null || subTag.trim().isEmpty())
		{
			return C.DARK_GREEN + "[" + C.DARK_GRAY + "GSW" + C.DARK_GREEN + "]" + C.GRAY + ": ";
		}

		return C.DARK_GREEN + "[" + C.DARK_GRAY + "GSW" + C.GRAY + " - " + C.WHITE + subTag.trim() + C.DARK_GREEN + "]" + C.GRAY + ": ";
	}
}
