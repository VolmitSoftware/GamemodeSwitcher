package com.volmit.gsw;

import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import primal.bukkit.command.PrimalCommand;
import primal.bukkit.command.PrimalSender;
import primal.bukkit.plugin.PrimalPlugin;
import primal.lang.collection.GMap;
import primal.logic.format.F;
import primal.logic.queue.ChronoLatch;
import primal.util.text.C;

public class GamemodeSwitcher extends PrimalPlugin implements Listener
{
	private GMap<Player, ChronoLatch> doubleTaps;
	private GMap<Player, Integer> doublecounts;

	@Override
	public void start()
	{
		registerListener(this);
		doubleTaps = new GMap<Player, ChronoLatch>();
		doublecounts = new GMap<Player, Integer>();
		registerCommand(new PrimalCommand("gsw")
		{
			public boolean handle(PrimalSender sender, String[] args)
			{
				if(!sender.hasPermission("gsw.use"))
				{
					sender.sendMessage("Insufficient Permission");
					return true;
				}

				if(sender.player().getGameMode().equals(GameMode.SPECTATOR))
				{
					sender.sendMessage(C.WHITE + "SHIFT, SHIFT, SHIFT " + C.GRAY + " -> " + C.GREEN + GameMode.SURVIVAL);
				}

				else
				{
					sender.sendMessage(C.WHITE + "F, F " + C.GRAY + " -> " + C.GREEN + getGameMode(sender.player().getGameMode(), false));
					sender.sendMessage(C.WHITE + "SHIFT + (F, F) " + C.GRAY + " -> " + C.GREEN + getGameMode(sender.player().getGameMode(), true));
				}

				return true;
			}
		});
	}

	@Override
	public void stop()
	{

	}

	@EventHandler
	public void on(PlayerSwapHandItemsEvent e)
	{
		if(!e.getPlayer().hasPermission("gsw.use"))
		{
			return;
		}

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

	@EventHandler
	public void on(PlayerToggleSneakEvent e)
	{
		if(!e.getPlayer().hasPermission("gsw.use"))
		{
			return;
		}

		if(!e.getPlayer().getGameMode().equals(GameMode.SPECTATOR))
		{
			return;
		}

		if(!doubleTaps.containsKey(e.getPlayer()))
		{
			doubleTaps.put(e.getPlayer(), new ChronoLatch(600, false));
			return;
		}

		else
		{
			if(!doubleTaps.get(e.getPlayer()).flip())
			{
				if(!doublecounts.containsKey(e.getPlayer()))
				{
					doublecounts.put(e.getPlayer(), 2);
				}

				doublecounts.put(e.getPlayer(), doublecounts.get(e.getPlayer()) - 1);

				if(doublecounts.get(e.getPlayer()) <= 0)
				{
					doublecounts.remove(e.getPlayer());
					doubleTapped(e.getPlayer());
				}
			}

			else
			{
				doublecounts.remove(e.getPlayer());
			}

			doubleTaps.remove(e.getPlayer());
		}
	}

	private GameMode getGameMode(GameMode current, boolean shift)
	{
		GameMode g = current;
		GameMode out = null;
		switch(g)
		{
			case ADVENTURE:
				out = shift ? GameMode.SURVIVAL : GameMode.CREATIVE;
				break;
			case CREATIVE:
				out = shift ? GameMode.SPECTATOR : GameMode.SURVIVAL;
				break;
			case SPECTATOR:
				out = GameMode.CREATIVE;
				break;
			case SURVIVAL:
				out = shift ? GameMode.ADVENTURE : GameMode.CREATIVE;
				break;
			default:
				break;
		}

		return out;
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
				out = GameMode.CREATIVE;
				break;
			case SURVIVAL:
				out = p.isSneaking() ? GameMode.ADVENTURE : GameMode.CREATIVE;
				break;
			default:
				break;
		}

		p.setGameMode(out);
		p.sendMessage(getTag() + "Switched to " + C.DARK_GREEN + F.capitalize(out.name().toLowerCase()));
		p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_DIAMOND, 1f, 1f);
		p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 0.25f);
		p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_ELYTRA, 1f, 1.75f);

		if(out.equals(GameMode.CREATIVE))
		{
			p.setAllowFlight(true);
			p.setFlying(true);
		}

		else if(out.equals(GameMode.SPECTATOR))
		{
			p.sendMessage(getTag() + "Triple tap shift/sneak to exit spectator.");
		}

		else
		{
			p.setFlying(false);
			p.setAllowFlight(false);
		}
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
