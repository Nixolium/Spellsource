package net.demilich.metastone.game.logic;

import co.paralleluniverse.fibers.Suspendable;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import net.demilich.metastone.game.*;
import net.demilich.metastone.game.actions.*;
import net.demilich.metastone.game.cards.*;
import net.demilich.metastone.game.cards.costmodifier.CardCostModifier;
import net.demilich.metastone.game.decks.Deck;
import net.demilich.metastone.game.entities.*;
import net.demilich.metastone.game.entities.heroes.Hero;
import net.demilich.metastone.game.entities.heroes.HeroClass;
import net.demilich.metastone.game.entities.minions.Minion;
import net.demilich.metastone.game.entities.minions.Race;
import net.demilich.metastone.game.entities.weapons.Weapon;
import net.demilich.metastone.game.events.*;
import net.demilich.metastone.game.heroes.powers.HeroPowerCard;
import net.demilich.metastone.game.spells.DamageSpell;
import net.demilich.metastone.game.spells.DestroySpell;
import net.demilich.metastone.game.spells.MetaSpell;
import net.demilich.metastone.game.spells.Spell;
import net.demilich.metastone.game.spells.SpellUtils;
import net.demilich.metastone.game.spells.aura.Aura;
import net.demilich.metastone.game.spells.desc.SpellArg;
import net.demilich.metastone.game.spells.desc.SpellDesc;
import net.demilich.metastone.game.spells.desc.SpellFactory;
import net.demilich.metastone.game.spells.desc.filter.EntityFilter;
import net.demilich.metastone.game.spells.desc.filter.FilterArg;
import net.demilich.metastone.game.spells.desc.trigger.TriggerDesc;
import net.demilich.metastone.game.spells.trigger.DamageCausedTrigger;
import net.demilich.metastone.game.spells.trigger.DamageReceivedTrigger;
import net.demilich.metastone.game.spells.trigger.IGameEventListener;
import net.demilich.metastone.game.spells.trigger.SpellCastedTrigger;
import net.demilich.metastone.game.spells.trigger.SpellTrigger;
import net.demilich.metastone.game.spells.trigger.TriggerManager;
import net.demilich.metastone.game.spells.trigger.secrets.Secret;
import net.demilich.metastone.game.targeting.*;
import net.demilich.metastone.utils.MathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The game logic class implements the basic primitives of gameplay.
 * <p>
 * This class processes all the changes to a game state (a variety of fields in a {@link GameContext} between requests
 * for player actions. It does not make player action requests itself (the {@link GameContext} is responsible for
 * that).
 * <p>
 * Most functions will accept a {@link GameContext} as an argument and mutate it. You can use {@link
 * GameContext#clone()} to create an "immutable" equivalent behaviour.
 * <p>
 * Most effects are encoded in {@link Spell} classes, which subsequently call functions in this class. However, a few
 * key functions are called by {@link GameAction#execute(GameContext, int)} calls directly, like {@link #summon(int,
 * Minion, Card, int, boolean)} and {@link #fight(Player, Actor, Actor)}.
 */
public class GameLogic implements Cloneable, Serializable {
	protected static Logger logger = LoggerFactory.getLogger(GameLogic.class);
	/**
	 * The maximum number of {@link Minion} entities that can be on a {@link Zones#BATTLEFIELD}.
	 */
	public static final int MAX_MINIONS = 7;
	/**
	 * The maximum number of {@link Card} entities that can be in a {@link Zones#HAND}.
	 */
	public static final int MAX_HAND_CARDS = 10;
	/**
	 * The default maximum {@link Attribute#HP} a {@link Hero} can have.
	 */
	public static final int MAX_HERO_HP = 30;
	/**
	 * The number of {@link Card} entities that a {@link Player} should start with at the beginning of a game.
	 */
	public static final int STARTER_CARDS = 3;
	/**
	 * The maximum amount of mana a {@link Player} can have at the start of a turn. Some effects allow a player to spend
	 * more than {@link #MAX_MANA} mana in a turn, but never start with more.
	 */
	public static final int MAX_MANA = 10;
	/**
	 * The maximum number of {@link Secret} entities that can be in a {@link Zones#SECRET}.
	 */
	public static final int MAX_SECRETS = 5;
	/**
	 * The maximum number of {@link Card} entities that a {@link Player} can build a {@link Deck} with. Some effects,
	 * like Prince Malchezaar's text, allow the player to start a game with more than {@link #DECK_SIZE} cards.
	 */
	public static final int DECK_SIZE = 30;
	/**
	 * The maximum number of {@link Card} entities that can be in a {@link Zones#DECK} zone.
	 */
	public static final int MAX_DECK_SIZE = 60;
	/**
	 * The maximum number of turns until a game is forced into a draw.
	 */
	public static final int TURN_LIMIT = 100;
	/**
	 * The number of attacks gained by {@link Attribute#WINDFURY}.
	 *
	 * @see Actor#canAttackThisTurn() for the complete attack count logic.
	 */
	public static final int WINDFURY_ATTACKS = 2;
	/**
	 * The number of attacks gained by {@link Attribute#MEGA_WINDFURY}.
	 *
	 * @see Actor#canAttackThisTurn() for the complete attack count logic.
	 */
	public static final int MEGA_WINDFURY_ATTACKS = 4;
	/**
	 * This {@link Set} stores each {@link Attribute} that is not cleared when an {@link Entity} is silenced.
	 *
	 * @see #silence(int, Minion) for the silence game logic.
	 */
	public static final Set<Attribute> immuneToSilence = new HashSet<>();
	/**
	 * A prefix appended to cards that are temporarily generated by game rules.
	 *
	 * @see GameLogic#generateCardId() for the situation where card IDs need to be generated.
	 */
	public static final String TEMP_CARD_LABEL = "temp_card_id_";
	private static final int INFINITE = -1;
	protected final TargetLogic targetLogic = new TargetLogic();
	private final ActionLogic actionLogic = new ActionLogic();
	private final SpellFactory spellFactory = new SpellFactory();
	private IdFactory idFactory;
	private final Random random = new Random();
	protected transient GameContext context;
	private boolean loggingEnabled = false;
	private final int MAX_HISTORY_ENTRIES = 100;
	private ArrayDeque<String> debugHistory = new ArrayDeque<>();

	static {
		immuneToSilence.add(Attribute.HP);
		immuneToSilence.add(Attribute.MAX_HP);
		immuneToSilence.add(Attribute.BASE_HP);
		immuneToSilence.add(Attribute.BASE_ATTACK);
		immuneToSilence.add(Attribute.SUMMONING_SICKNESS);
		immuneToSilence.add(Attribute.AURA_ATTACK_BONUS);
		immuneToSilence.add(Attribute.AURA_HP_BONUS);
		immuneToSilence.add(Attribute.AURA_UNTARGETABLE_BY_SPELLS);
		immuneToSilence.add(Attribute.RACE);
		immuneToSilence.add(Attribute.NUMBER_OF_ATTACKS);
	}

	/**
	 * Creates a new game logic instance whose next ID generated for an {@link Entity#setId(int)} argument will be zero.
	 */
	public GameLogic() {
		idFactory = new IdFactory();
	}

	/**
	 * Creates a game logic instance with an ID factory. Typically you can create an ID factory and set its current ID
	 * to whichever number you want to be the next entity ID created by this game logic.
	 *
	 * @param idFactory An existing ID factory.
	 */
	private GameLogic(IdFactory idFactory) {
		this.idFactory = idFactory;
	}

	/**
	 * Adds a {@link IGameEventListener} to a specified {@link Entity}. These are typically {@link SpellTrigger}
	 * instances that react to game events.
	 *
	 * @param player            Usually the current turn player.
	 * @param gameEventListener A game event listener, like a {@link Aura}, {@link Secret} or {@link CardCostModifier}.
	 * @param target            The {@link Entity} that will be pointed to by {@link IGameEventListener#getHostReference()}.
	 * @see TriggerManager#fireGameEvent(GameEvent, List) for the complete implementation of triggers.
	 */
	@Suspendable
	public void addGameEventListener(Player player, IGameEventListener gameEventListener, Entity target) {
		if (isLoggingEnabled()) {
			debugHistory.add("Player " + player.getId() + " has set event listener " + gameEventListener.getClass().getName() + " from entity " + target.getName() + "[Reference ID: " + target.getId() + "]");
		}

		gameEventListener.setHost(target);
		if (!gameEventListener.hasPersistentOwner() || gameEventListener.getOwner() == Entity.NO_OWNER) {
			gameEventListener.setOwner(player.getId());
		}

		gameEventListener.onAdd(context);
		context.addTrigger(gameEventListener);
		log("New spelltrigger was added for {} on {}: {}", player.getName(), target, gameEventListener);
	}

	/**
	 * Specifically add a card cost modifier to the game, which is a special kind of {@link IGameEventListener}.
	 *
	 * @param player           Usually the current turn player.
	 * @param cardCostModifier The card cost modifier.
	 * @param target           The {@link Card} whose cost should be modified.
	 */
	@Suspendable
	public void addManaModifier(Player player, CardCostModifier cardCostModifier, Entity target) {
		context.getCardCostModifiers().add(cardCostModifier);
		addGameEventListener(player, cardCostModifier, target);
	}

	/**
	 * Handles combo and mana cost modifier removal for the card played in the
	 * {@link PlayCardAction#execute(GameContext, int)} method. Can probably be inlined.
	 *
	 * @param playerId      The player index
	 * @param cardReference A reference to the card.
	 */
	@Suspendable
	public void afterCardPlayed(int playerId, CardReference cardReference) {
		Player player = context.getPlayer(playerId);

		player.modifyAttribute(Attribute.COMBO, +1);
		Card card = context.resolveCardReference(cardReference);

		card.getAttributes().remove(Attribute.MANA_COST_MODIFIER);
	}

	/**
	 * Calculates how much to amplify an attribute by. This is typically either a spell damage or healing effect
	 * multiplier.
	 * <p>
	 * This implements Prophet Velen.
	 *
	 * @param player    The friendly player.
	 * @param baseValue The value to multiply.
	 * @param attribute The attribute to look up in all entities.
	 * @return The newly calculate spell or healing value.
	 * @see Attribute#HEAL_AMPLIFY_MULTIPLIER for the healing amplification attribute.
	 * @see Attribute#SPELL_AMPLIFY_MULTIPLIER for the spell damage amplification attribute.
	 */
	@Suspendable
	public int applyAmplify(Player player, int baseValue, Attribute attribute) {
		int amplify = getTotalAttributeMultiplier(player, attribute);
		return baseValue * amplify;
	}

	/**
	 * Gives an {@link Entity} a boolean {@link Attribute}.
	 * <p>
	 * This addresses bugs with {@link Attribute#WINDFURY} and should be the place for special rules around attributes
	 * in the future.
	 *
	 * @param entity An {@link Entity}
	 * @param attr   An {@link Attribute}
	 */
	@Suspendable
	public void applyAttribute(Entity entity, Attribute attr) {
		if (attr == Attribute.MEGA_WINDFURY && entity.hasAttribute(Attribute.WINDFURY) && !entity.hasAttribute(Attribute.MEGA_WINDFURY)) {
			entity.modifyAttribute(Attribute.NUMBER_OF_ATTACKS, MEGA_WINDFURY_ATTACKS - WINDFURY_ATTACKS);
		} else if (attr == Attribute.WINDFURY && !entity.hasAttribute(Attribute.WINDFURY) && !entity.hasAttribute(Attribute.MEGA_WINDFURY)) {
			entity.modifyAttribute(Attribute.NUMBER_OF_ATTACKS, WINDFURY_ATTACKS - 1);
		} else if (attr == Attribute.MEGA_WINDFURY && !entity.hasAttribute(Attribute.WINDFURY) && !entity.hasAttribute(Attribute.MEGA_WINDFURY)) {
			entity.modifyAttribute(Attribute.NUMBER_OF_ATTACKS, MEGA_WINDFURY_ATTACKS - 1);
		}
		entity.setAttribute(attr);
		log("Applying attr {} to {}", attr, entity);
	}

	/**
	 * Applies hero power damage increases
	 *
	 * @param player    The Player to grab additional hero power damage from
	 * @param baseValue The base damage the hero power does
	 * @return Increased hero power damage
	 */
	@Suspendable
	public int applyHeroPowerDamage(Player player, int baseValue) {
		int spellpower = getTotalAttributeValue(player, Attribute.HERO_POWER_DAMAGE);
		return baseValue + spellpower;
	}

	/**
	 * Applies spell damage increases
	 *
	 * @param player    The Player to grab the additional spell damage from
	 * @param source    The source Card
	 * @param baseValue The base damage the spell does
	 * @return Increased spell damage
	 */
	@Suspendable
	public int applySpellpower(Player player, Entity source, int baseValue) {
		int spellpower = getTotalAttributeValue(player, Attribute.SPELL_DAMAGE)
				+ getTotalAttributeValue(context.getOpponent(player), Attribute.OPPONENT_SPELL_DAMAGE);
		if (source.hasAttribute(Attribute.SPELL_DAMAGE_MULTIPLIER)) {
			spellpower *= source.getAttributeValue(Attribute.SPELL_DAMAGE_MULTIPLIER);
		}
		return baseValue + spellpower;
	}

	/**
	 * Assigns an {@link Entity#id} and {@link Entity#ownerIndex} to each {@link Card} in a given {@link Deck}.
	 *
	 * @param cardList   The {@link Deck} whose cards should have IDs and owners assigned.
	 * @param ownerIndex The owner to assign to this {@link CardList}
	 */
	@Suspendable
	protected void assignCardIds(CardList cardList, int ownerIndex) {
		for (Card card : cardList) {
			card.setId(getIdFactory().generateId());
			card.setOwner(ownerIndex);
		}
	}

	/**
	 * Checks if any {@link Entity} in the game has the given {@link Attribute}.
	 *
	 * @param attr The attribute to look up.
	 * @return {@code true} if any {@link Entity} has the given attribute.
	 */
	@Suspendable
	public boolean attributeExists(Attribute attr) {
		return context.getEntities().anyMatch(e -> e.hasAttribute(attr));
	}

	/**
	 * Determines whether the given player can play the given card. Useful for drawing green borders around cards to
	 * signal to an end user that they can play a particular card. Takes into account whether or not a spell that
	 * requires targets has possible targets in the game.
	 *
	 * @param playerId      The player whose point of view should be considered for this method.
	 * @param cardReference A reference to the card.
	 * @return {@code true} if the card can be played.
	 */
	@Suspendable
	public boolean canPlayCard(int playerId, CardReference cardReference) {
		Player player = context.getPlayer(playerId);
		Card card = context.resolveCardReference(cardReference);
		// A player cannot play a card the player does not own.
		if (card.getOwner() != player.getOwner()
				&& card.getOwner() != Entity.NO_OWNER) {
			return false;
		}
		int manaCost = getModifiedManaCost(player, card);
		if (card.getCardType().isCardType(CardType.SPELL)
				&& player.hasAttribute(Attribute.SPELLS_COST_HEALTH)
				&& player.getHero().getEffectiveHp() < manaCost) {
			return false;
		} else if (card.getCardType().isCardType(CardType.MINION)
				&& (Race) card.getAttribute(Attribute.RACE) == Race.MURLOC
				&& player.hasAttribute(Attribute.MURLOCS_COST_HEALTH)
				&& player.getHero().getEffectiveHp() < manaCost) {
			return false;
		} else if (player.getMana() < manaCost && manaCost != 0
				&& !((card.getCardType().isCardType(CardType.SPELL)
				&& player.hasAttribute(Attribute.SPELLS_COST_HEALTH))
				|| ((Race) card.getAttribute(Attribute.RACE) == Race.MURLOC
				&& player.hasAttribute(Attribute.MURLOCS_COST_HEALTH)))) {
			return false;
		}
		if (card.getCardType().isCardType(CardType.HERO_POWER)) {
			HeroPowerCard power = (HeroPowerCard) card;
			int heroPowerUsages = getGreatestAttributeValue(player, Attribute.HERO_POWER_USAGES);
			if (heroPowerUsages == 0) {
				heroPowerUsages = 1;
			}
			if (heroPowerUsages != INFINITE && power.hasBeenUsed() >= heroPowerUsages) {
				return false;
			}
		} else if (card.getCardType().isCardType(CardType.MINION)) {
			return canSummonMoreMinions(player);
		}

		if (card instanceof SpellCard) {
			SpellCard spellCard = (SpellCard) card;
			return spellCard.canBeCast(context, player);
		}
		return true;
	}

	/**
	 * Determines whether a player can play a {@link Secret}.
	 * <p>
	 * Players cannot have more than one copy of the same Secret active at any one time. Players are unable to play
	 * Secret cards which match one of their active Secrets.
	 * <p>
	 * When played directly from the hand, players can have up to 5 different Secrets active at a time. Once this limit
	 * is reached, the player will be unable to play further Secret cards.
	 *
	 * @param player The player whose {@link Zones#SECRET} zone should be inspected.
	 * @param card   The secret card being evaluated.
	 * @return {@code true} if the secret can be played.
	 */
	public boolean canPlaySecret(Player player, SecretCard card) {
		return player.getSecrets().size() < MAX_SECRETS && !player.getSecretCardIds().contains(card.getCardId());
	}

	/**
	 * Determines whether or not a player can summon more minions.
	 *
	 * @param player The player whose {@link Zones#BATTLEFIELD} zone should be inspected for minions.
	 * @return {@code true} if the player can summon more minions.
	 */
	public boolean canSummonMoreMinions(Player player) {
		return player.getMinions().size() < MAX_MINIONS;
	}

	/**
	 * Casts one of the two options of a "Choose One" spell and handles all its sophisticated rules.
	 * <p>
	 * Choose One is an ability which allows a player to choose one of multiple possible effects when the card is played
	 * from the hand. Cards with this ability are limited to the druid class.
	 * <p>
	 * Choose One effects are similar to Discover effects, and certain other cards such as Tracking, which also allow
	 * you to choose between multiple options.
	 *
	 * @param playerId        The player casting the choose one spell.
	 * @param spellDesc       The {@link SpellDesc} of the chosen card, not the parent card that contains the choices.
	 * @param sourceReference The source of the spell, typically the original {@link ChooseOneCard}.
	 * @param targetReference The target selected for this choice.
	 * @param cardId          The card that was chosen.
	 */
	@Suspendable
	public void castChooseOneSpell(int playerId, SpellDesc spellDesc, EntityReference sourceReference, EntityReference targetReference, String cardId) {
		Player player = context.getPlayer(playerId);
		Entity source = null;
		if (sourceReference != null) {
			try {
				source = context.resolveSingleTarget(sourceReference);
			} catch (Exception e) {
				e.printStackTrace();
				logger.error("Error resolving source entity while casting spell: " + spellDesc);
			}
		}
		EntityReference spellTarget = spellDesc.hasPredefinedTarget() ? spellDesc.getTarget() : targetReference;
		List<Entity> targets = targetLogic.resolveTargetKey(context, player, source, spellTarget);
		Card sourceCard = null;
		SpellCard chosenCard = (SpellCard) context.getCardById(cardId);
		sourceCard = source.getEntityType() == EntityType.CARD ? (Card) source : null;
		if (!spellDesc.hasPredefinedTarget() && targets != null && targets.size() == 1) {
			if (chosenCard.getTargetRequirement() != TargetSelection.NONE) {
				context.getEnvironment().remove(Environment.TARGET_OVERRIDE);
				context.getEnvironment().put(Environment.CHOOSE_ONE_CARD, chosenCard.getCardId());
				GameEvent spellTargetEvent = new TargetAcquisitionEvent(context, playerId, ActionType.SPELL, chosenCard, targets.get(0));
				context.fireGameEvent(spellTargetEvent);
				Entity targetOverride = context
						.resolveSingleTarget((EntityReference) context.getEnvironment().get(Environment.TARGET_OVERRIDE));
				if (targetOverride != null && targetOverride.getId() != IdFactory.UNASSIGNED) {
					targets.remove(0);
					targets.add(targetOverride);
					spellDesc = spellDesc.addArg(SpellArg.FILTER, null);
					log("Target for spell {} has been changed! New target {}", chosenCard, targets.get(0));
				}
			}
		}
		try {
			Spell spell = spellFactory.getSpell(spellDesc);
			spell.cast(context, player, spellDesc, source, targets);
		} catch (Exception e) {
			if (source != null) {
				logger.error("Error while playing card: " + source.getName());
			}
			logger.error("Error while casting spell: " + spellDesc);
			panicDump();
			e.printStackTrace();
		}

		context.getEnvironment().remove(Environment.TARGET_OVERRIDE);
		context.getEnvironment().remove(Environment.CHOOSE_ONE_CARD);

		checkForDeadEntities();
		if (targets == null || targets.size() != 1) {
			context.fireGameEvent(new AfterSpellCastedEvent(context, playerId, sourceCard, null));
		} else {
			context.fireGameEvent(new AfterSpellCastedEvent(context, playerId, sourceCard, targets.get(0)));
		}
	}

	/*
	 * Casts a spell.
	 * @param playerId The casting player.
	 * @param spellDesc The {@link SpellDesc} for the spell.
	 * @param sourceReference The source of the spell (typically the card), or {@code null} if it doesn't have one.
	 * @param targetReference The selected target of the spell, or {@code null} if it doesn't have one.
	 * @param childSpell When true, this spell is implementing an effect rather than what a player would think of as a
	 * "spell"—a single card.
	 * @see #castSpell(int, SpellDesc, EntityReference, EntityReference, TargetSelection, boolean) for complete documentation.
	 */
	@Suspendable
	public void castSpell(int playerId, SpellDesc spellDesc, EntityReference sourceReference, EntityReference targetReference,
	                      boolean childSpell) {
		castSpell(playerId, spellDesc, sourceReference, targetReference, TargetSelection.NONE, childSpell);
	}

	/**
	 * Casts a spell.
	 * <p>
	 * This method uses the {@link SpellDesc} (a {@link Map} of {@link SpellArg}, {@link Object}) to figure out what the
	 * spell should do. The {@link SpellFactory#getSpell(SpellDesc)} method creates an instance of the {@link Spell}
	 * class returned by {@code spellDesc.getSpellClass()}, then calls its {@link Spell#onCast(GameContext, Player,
	 * SpellDesc, Entity, Entity)} method to actually execute the code of the spell.
	 * <p>
	 * For example, imagine a spell, "Deal 2 damage to all Murlocs." This would have a {@link SpellDesc} (1) whose
	 * {@link SpellArg#CLASS} would be {@link DamageSpell}, (2) whose {@link SpellArg#FILTER} would be an instance of
	 * {@link EntityFilter} with {@link FilterArg#RACE} as {@link Race#MURLOC}, (3) whose {@link SpellArg#VALUE} would
	 * be {@code 2} to deal 2 damage, and whose (4) {@link SpellArg#TARGET} would be {@link
	 * EntityReference#ALL_MINIONS}.
	 * <p>
	 * Effects can modify spells or create new ones. {@link SpellDesc} allows the code to modify the "code" of a spell.
	 * <p>
	 * This method is responsible for turning the {@link SpellArg#CLASS} argument into a spell instance. The particular
	 * spell class is then responsible for interpreting the rest of its arguments. This code also handles the player's
	 * chosen target whenever a spell had a target selection.
	 *
	 * @param playerId        The players from whose point of view this spell is cast (typically the owning player).
	 * @param spellDesc       A description of the spell.
	 * @param sourceReference The origin of the spell. This is typically the {@link Minion} if the spell is a battlecry
	 *                        or deathrattle; or, the {@link SpellCard} if this spell is coming from a card.
	 * @param targetReference A reference to the target the user selected, if the spell was supposed to have a target.
	 * @param targetSelection If not {@code null}, the spell must have at least one {@link Entity} satisfying this
	 *                        target selection requirement in order for it to be cast.
	 * @param childSpell      When {@code true}, this spell is part an effect, like one of the {@link SpellArg#SPELLS}
	 *                        of a {@link MetaSpell}, and so it shouldn't trigger the firing of events like {@link
	 *                        SpellCastedTrigger}. When {@code false}, this spell is what a player would interpret as a
	 *                        spell coming from a card (a "spell" in the sense of what is written on cards). Battlecries
	 *                        and deathrattles are, unusually, {@code false} (not) child spells.
	 * @see Spell#cast(GameContext, Player, SpellDesc, Entity, List) for the code that interprets the {@link
	 * SpellArg#FILTER}, and {@link SpellArg#RANDOM_TARGET} arguments.
	 * @see Spell#onCast(GameContext, Player, SpellDesc, Entity, Entity) for the function that typically has the
	 * spell-specific code (e.g., {@link DamageSpell#onCast(GameContext, Player, SpellDesc, Entity, Entity)} actually
	 * implements the logic of a damage spell and interprets the {@link SpellArg#VALUE} attribute of the {@link
	 * SpellDesc} as damage.
	 * @see MetaSpell for the mechanism that multiple spells as children are chained together to create an effect.
	 * @see ActionLogic#rollout(GameAction, GameContext, Player, Collection) for the code that turns a target selection
	 * into actions the player can take.
	 * @see PlaySpellCardAction#play(GameContext, int) for the call to this function that a player actually does when
	 * they play a {@link SpellCard} (as opposed to a battlecry or deathrattle).
	 * @see BattlecryAction#execute(GameContext, int) for the call to this function that demonstrates a battlecry
	 * effect. Battlecries are spells in the sense that they are effects, though they're not {@link SpellCard} objects.
	 */
	@Suspendable
	public void castSpell(int playerId, SpellDesc spellDesc, EntityReference sourceReference, EntityReference targetReference,
	                      TargetSelection targetSelection, boolean childSpell) {
		Player player = context.getPlayer(playerId);
		Entity source = null;
		if (sourceReference != null) {
			try {
				source = context.resolveSingleTarget(sourceReference);
			} catch (Exception e) {
				e.printStackTrace();
				logger.error("Error resolving source entity while casting spell: " + spellDesc);
			}

		}
		//SpellCard spellCard = null;
		EntityReference spellTarget = spellDesc.hasPredefinedTarget() ? spellDesc.getTarget() : targetReference;
		List<Entity> targets = targetLogic.resolveTargetKey(context, player, source, spellTarget);
		// target can only be changed when there is one target
		// note: this code block is basically exclusively for the SpellBender
		// Secret, but it can easily be expanded if targets of area of effect
		// spell should be changeable as well
		Card sourceCard = null;
		if (source != null) {
			sourceCard = source.getEntityType() == EntityType.CARD ? (Card) source : null;
		}
		if (sourceCard != null && sourceCard.getCardType().isCardType(CardType.SPELL) && !spellDesc.hasPredefinedTarget() && targets != null
				&& targets.size() == 1) {
			if (sourceCard.getCardType().isCardType(CardType.SPELL) && targetSelection != TargetSelection.NONE && !childSpell) {
				GameEvent spellTargetEvent = new TargetAcquisitionEvent(context, playerId, ActionType.SPELL, sourceCard, targets.get(0));
				context.fireGameEvent(spellTargetEvent);
				Entity targetOverride = context
						.resolveSingleTarget((EntityReference) context.getEnvironment().get(Environment.TARGET_OVERRIDE));
				if (targetOverride != null && targetOverride.getId() != IdFactory.UNASSIGNED) {
					targets.remove(0);
					targets.add(targetOverride);
					spellDesc = spellDesc.addArg(SpellArg.FILTER, null);
					log("Target for spell {} has been changed! New target {}", sourceCard, targets.get(0));
				}
			}

		}

		Spell spell = spellFactory.getSpell(spellDesc);
		spell.cast(context, player, spellDesc, source, targets);
		if (sourceCard != null && sourceCard.getCardType().isCardType(CardType.SPELL) && !childSpell) {
			context.getEnvironment().remove(Environment.TARGET_OVERRIDE);

			checkForDeadEntities();
			if (targets == null || targets.size() != 1) {
				context.fireGameEvent(new AfterSpellCastedEvent(context, playerId, sourceCard, null));
			} else {
				context.fireGameEvent(new AfterSpellCastedEvent(context, playerId, sourceCard, targets.get(0)));
			}
		}
	}

	/**
	 * Changes the player's hero.
	 * <p>
	 * Implements Lord Jaraxxus.
	 *
	 * @param player The player whose hero to change.
	 * @param hero   The new hero the player will have.
	 */
	public void changeHero(Player player, Hero hero) {
		hero.setId(player.getHero().getId());
		if (hero.getHeroClass() == null || hero.getHeroClass() == HeroClass.ANY) {
			hero.setHeroClass(player.getHero().getHeroClass());
		}

		log("{}'s hero has been changed to {}", player.getName(), hero);
		hero.setOwner(player.getId());
		hero.setWeapon(player.getHero().getWeapon());
		player.setHero(hero);
		hero.getHeroPower().setId(getIdFactory().generateId());
		hero.getHeroPower().setOwner(hero.getOwner());
		refreshAttacksPerRound(hero);
	}

	/**
	 * Removes entities for whom {@link Entity#isDestroyed()} is true, moving them to the {@link Zones#GRAVEYARD} and
	 * triggering their deathrattles with {@link #resolveDeathrattles(Player, Actor)}.
	 * <p>
	 * Since deathrattles may destroy other entities (e.g., a {@link DamageSpell} deathrattle), this function calls
	 * itself recursively until there are no more dead entities on the board.
	 */
	@Suspendable
	public void checkForDeadEntities() {
		checkForDeadEntities(0);
	}

	/**
	 * Checks all player minions and weapons for destroyed actors and proceeds with the removal in correct order.
	 *
	 * @param i The number of times this method has been called to avoid infinite death checking.
	 */
	@Suspendable
	private void checkForDeadEntities(int i) {
		// sanity check, this method should never call itself that often
		if (i > 20) {
			panicDump();
			throw new RuntimeException("Infinite death checking loop");
		}

		List<Actor> destroyList = new ArrayList<>();
		for (Player player : context.getPlayers()) {

			if ((player.getHero().isDestroyed() || player.hasAttribute(Attribute.DESTROYED)) &&
					player.getHero().getZone() != Zones.GRAVEYARD) {
				destroyList.add(player.getHero());
			}

			for (Minion minion : player.getMinions()) {
				if (minion.isDestroyed()) {
					destroyList.add(minion);
				}
			}
			if (player.getHero().getWeapon() != null && player.getHero().getWeapon().isDestroyed()) {
				destroyList.add(player.getHero().getWeapon());
			}
		}

		if (destroyList.isEmpty()) {
			return;
		}

		// sort the destroyed actors by their id. This implies that actors with a lower id entered the game earlier than those with higher ids!
		Collections.sort(destroyList, (a1, a2) -> Integer.compare(a1.getId(), a2.getId()));
		// this method performs the actual removal
		destroy(destroyList.toArray(new Actor[0]));
		if (context.gameDecided()) {
			return;
		}
		// deathrattles have been resolved, which may lead to other actors being destroyed now, so we need to check again
		checkForDeadEntities(i + 1);
	}

	/**
	 * Clones the game logic. The only state in this instance is its debug history and the current ID of the ID Factory.
	 *
	 * @return A clone of this logic.
	 * @see IdFactory for the internal state of an {@link IdFactory}.
	 */
	@Override
	public GameLogic clone() {
		GameLogic clone = new GameLogic(getIdFactory().clone());
		clone.setLoggingEnabled(this.isLoggingEnabled());
		if (isLoggingEnabled()) {
			clone.debugHistory = this.debugHistory.clone();
		}
		clone.context = context;
		return clone;
	}

	/**
	 * Deals spell damage to a target.
	 *
	 * @param player     The originating player of the damage.
	 * @param target     The target to damage.
	 * @param baseDamage The base amount of damage to deal.
	 * @param source     The source of the damage.
	 * @return The amount of damage ultimately dealt, considering all on board effects.
	 * @see #damage(Player, Actor, int, Entity, boolean) for a complete description of the damage effect.
	 */
	@Suspendable
	public int damage(Player player, Actor target, int baseDamage, Entity source) {
		return damage(player, target, baseDamage, source, false);
	}

	/**
	 * Deals damage to a target.
	 * <p>
	 * Damage is measured by a number which is deducted from the armor first, followed by hitpoints, of an {@link
	 * Actor}. If the {@link Actor#getHp()} is reduced to zero (or below), it will be killed. Note that other types of
	 * harm that can be inflicted to characters (such as a {@link DestroySpell}, freeze effects and the card Equality)
	 * are not considered damage for game purposes and, although most damage is dealt through {@link #fight(Player,
	 * Actor, Actor)}, dealing damage is not considered an "fight" for game purposes.
	 * <p>
	 * Damage can activate a number of triggered effects, both from receiving it (such as Acolyte of Pain's {@link
	 * DamageReceivedTrigger}) and from dealing it (such as Water Elemental's {@link DamageCausedTrigger}). However,
	 * damage negated by an {@link Actor} with {@link Attribute#DIVINE_SHIELD} or {@link Attribute#IMMUNE} effects is
	 * not considered to have been successfully dealt, and thus will not trigger any on-damage triggered effects.
	 * <p>
	 * A {@link Hero} with nonzero {@link Hero#getArmor()} will have any damage deducted from their armor before their
	 * hitpoints: any damage beyond the {@link Actor}'s current Armor will be deducted from their hitpoints. Armor will
	 * not prevent damage from being dealt: damage dealt only to Armor still counts as damage for the purpose of effects
	 * such as Water Elemental and Floating Watcher.
	 *
	 * @param player            The originating player of the damage.
	 * @param target            The target to damage.
	 * @param baseDamage        The base amount of damage to deal.
	 * @param source            The source of the damage.
	 * @param ignoreSpellDamage When {@code true}, spell damage bonuses are not added to the damage dealt.
	 * @return
	 */
	@Suspendable
	public int damage(Player player, Actor target, int baseDamage, Entity source, boolean ignoreSpellDamage) {
		// sanity check to prevent StackOverFlowError with Mistress of Pain +
		// Auchenai Soulpriest
		if (target.getHp() < -100) {
			return 0;
		}
		int damage = baseDamage;
		Card sourceCard = source != null && source.getEntityType() == EntityType.CARD ? (Card) source : null;
		if (!ignoreSpellDamage && sourceCard != null) {
			if (sourceCard.getCardType().isCardType(CardType.SPELL)) {
				damage = applySpellpower(player, source, baseDamage);
			} else if (sourceCard.getCardType().isCardType(CardType.HERO_POWER)) {
				damage = applyHeroPowerDamage(player, damage);
			}
			if (sourceCard.getCardType().isCardType(CardType.SPELL) || sourceCard.getCardType().isCardType(CardType.HERO_POWER)) {
				damage = applyAmplify(player, damage, Attribute.SPELL_AMPLIFY_MULTIPLIER);
			}
		}
		int damageDealt = 0;
		if (target.hasAttribute(Attribute.TAKE_DOUBLE_DAMAGE)) {
			damage *= 2;
		}
		context.getDamageStack().push(damage);
		context.fireGameEvent(new PreDamageEvent(context, target, source));
		damage = context.getDamageStack().pop();
		if (damage > 0) {
			source.getAttributes().remove(Attribute.STEALTH);
		}
		switch (target.getEntityType()) {
			case MINION:
				damageDealt = damageMinion((Actor) target, damage);
				break;
			case HERO:
				damageDealt = damageHero((Hero) target, damage);
				break;
			default:
				break;
		}

		target.setAttribute(Attribute.LAST_HIT, damageDealt);
		if (damageDealt > 0) {
			DamageEvent damageEvent = new DamageEvent(context, target, source, damageDealt);
			context.fireGameEvent(damageEvent);
			player.getStatistics().damageDealt(damageDealt);
		}

		return damageDealt;
	}

	private int damageHero(Hero hero, int damage) {
		if (hero.hasAttribute(Attribute.IMMUNE) || hasAttribute(context.getPlayer(hero.getOwner()), Attribute.IMMUNE_HERO)) {
			log("{} is IMMUNE and does not take damage", hero);
			return 0;
		}
		int effectiveHp = hero.getHp() + hero.getArmor();
		hero.modifyArmor(-damage);
		int newHp = Math.min(hero.getHp(), effectiveHp - damage);
		hero.setHp(newHp);
		log(hero.getName() + " receives " + damage + " damage, hp now: " + hero.getHp() + "(" + hero.getArmor() + ")");
		return damage;
	}

	@Suspendable
	private int damageMinion(Actor minion, int damage) {
		if (minion.hasAttribute(Attribute.DIVINE_SHIELD)) {
			removeAttribute(minion, Attribute.DIVINE_SHIELD);
			log("{}'s DIVINE SHIELD absorbs the damage", minion);
			return 0;
		}
		if (minion.hasAttribute(Attribute.IMMUNE)) {
			log("{} is IMMUNE and does not take damage", minion);
			return 0;
		}
		if (damage >= minion.getHp() && minion.hasAttribute(Attribute.CANNOT_REDUCE_HP_BELOW_1)) {
			damage = minion.getHp() - 1;
		}

		log("{} is damaged for {}", minion, damage);
		minion.setHp(minion.getHp() - damage);
		handleEnrage(minion);
		return damage;
	}

	/**
	 * Destroys the given targets, triggering their deathrattles if necessary.
	 *
	 * @param targets A list of {@link Actor} targets that should be destroyed.
	 * @see #checkForDeadEntities() for the code that actually finds dead entities as a result of effects and eventually
	 * destroys them.
	 */
	@Suspendable
	public void destroy(Actor... targets) {
		// Reverse the targets
		Map<Actor, EntityLocation> previousLocation = new HashMap<>();

		List<Actor> reversed = new ArrayList<>(Arrays.asList(targets));
		reversed.sort((a, b) -> -Integer.compare(a.getEntityLocation().getIndex(), b.getEntityLocation().getIndex()));

		for (Actor target : reversed) {
			removeSpellTriggers(target, false);
			previousLocation.put(target, target.getEntityLocation());
			log("{} is destroyed", target);
			target.moveOrAddTo(context, Zones.GRAVEYARD);
		}

		for (int i = 0; i < targets.length; i++) {
			Actor target = targets[i];
			Player owner = context.getPlayer(target.getOwner());
			switch (target.getEntityType()) {
				case HERO:
					log("Hero {} has been destroyed.", target.getName());
					applyAttribute(target, Attribute.DESTROYED);
					applyAttribute(context.getPlayer(target.getOwner()), Attribute.DESTROYED);
					break;
				case MINION:
					context.getEnvironment().put(Environment.KILLED_MINION, target.getReference());
					KillEvent killEvent = new KillEvent(context, target, previousLocation.get(target).getIndex());
					context.fireGameEvent(killEvent);
					context.getEnvironment().remove(Environment.KILLED_MINION);

					target.setAttribute(Attribute.DESTROYED);
					target.setAttribute(Attribute.DIED_ON_TURN, context.getTurn());
					break;
				case WEAPON:
					destroyWeapon((Weapon) target);
					break;
				case ANY:
				default:
					logger.error("Trying to destroy unknown entity type {}", target.getEntityType());
					break;
			}

			resolveDeathrattles(owner, target, previousLocation.get(target).getIndex());
		}
		for (Actor target : targets) {
			removeSpellTriggers(target, true);
		}

		context.fireGameEvent(new BoardChangedEvent(context));
	}

	@Suspendable
	private void destroyWeapon(Weapon weapon) {
		Player owner = context.getPlayer(weapon.getOwner());
		// resolveDeathrattles(owner, weapon);
		if (owner.getHero().getWeapon() != null && owner.getHero().getWeapon().getId() == weapon.getId()) {
			owner.getHero().setWeapon(null);
		}
		weapon.onUnequip(context, owner);
		context.fireGameEvent(new WeaponDestroyedEvent(context, weapon));
	}

	/**
	 * Determines who is the first player of a list of player IDs.
	 *
	 * @param playerIds The possible options for first player.
	 * @return The ID of the player that should go first.
	 */
	public int determineBeginner(int... playerIds) {
		return getRandom().nextBoolean() ? playerIds[0] : playerIds[1];
	}

	/**
	 * Discards a card from your hand, either through discard card effects or "overdraw" (forced
	 * destruction of cards due to too many cards in your hand).
	 * <p>
	 * Discarded cards are removed from the game, without activating Deathrattles. Discard effects are most commonly
	 * found on warlock cards. Discard effects are distinguished from overdraw, and Fel Reaver's remove from deck
	 * effect, both of which remove cards directly from the deck without entering the hand; and from Tracking's
	 * "discard" effect, which in fact removes cards directly from a special display zone without entering the hand.
	 * While similar to discard effects, neither is considered a discard for game purposes, and will not activate
	 * related effects.
	 * <p>
	 * This method handles all situations and correctly triggers a {@link DiscardEvent} only when a card is discarded
	 * from the hand.
	 *
	 * @param player The player that owns the card getting discarded.
	 * @param card   The card to discard.
	 * @see #receiveCard(int, Card) for more on receiving and drawing cards.
	 */
	@Suspendable
	public void discardCard(Player player, Card card) {
		logger.debug("{} discards {}", player.getName(), card);
		// only a 'real' discard should fire a DiscardEvent
		if (card.getZone() == Zones.HAND) {
			context.fireGameEvent(new DiscardEvent(context, player.getId(), card));
		}

		removeCard(player.getId(), card);
	}

	/**
	 * Draws a card for a player from the deck to the hand.
	 *
	 * When a {@link Deck} is empty, 
	 *
	 * @param playerId The player who should draw a card.
	 * @param source   The card that is the origin of the drawing effect, or {@code null} if this is the draw from the
	 *                 beginning of a turn
	 * @return The card that was drawn.
	 */
	@Suspendable
	public Card drawCard(int playerId, Entity source) {
		Player player = context.getPlayer(playerId);
		CardList deck = player.getDeck();
		if (deck.isEmpty()) {
			Hero hero = player.getHero();
			int fatigue = player.hasAttribute(Attribute.FATIGUE) ? player.getAttributeValue(Attribute.FATIGUE) : 0;
			fatigue++;
			player.setAttribute(Attribute.FATIGUE, fatigue);
			damage(player, hero, fatigue, hero);
			log("{}'s deck is empty, taking {} fatigue damage!", player.getName(), fatigue);
			player.getStatistics().fatigueDamage(fatigue);
			return null;
		}

		Card card = deck.getRandom();
		return drawCard(playerId, card, source);
	}

	@Suspendable
	public Card drawCard(int playerId, Card card, Entity source) {
		Player player = context.getPlayer(playerId);
		player.getStatistics().cardDrawn();
		receiveCard(playerId, card, source, true);
		return card;
	}

	public void drawSetAsideCard(int playerId, Card card) {
		if (card.getId() == IdFactory.UNASSIGNED) {
			card.setId(getIdFactory().generateId());
		}
		card.setOwner(playerId);
		Player player = context.getPlayer(playerId);
		player.getSetAsideZone().add(card);
	}

	@Suspendable
	public void endTurn(int playerId) {
		Player player = context.getPlayer(playerId);

		Hero hero = player.getHero();
		hero.getAttributes().remove(Attribute.TEMPORARY_ATTACK_BONUS);
		hero.getAttributes().remove(Attribute.HERO_POWER_USAGES);
		handleFrozen(hero);
		for (Minion minion : player.getMinions()) {
			minion.getAttributes().remove(Attribute.TEMPORARY_ATTACK_BONUS);
			handleFrozen(minion);
		}
		player.getAttributes().remove(Attribute.COMBO);
		hero.activateWeapon(false);
		log("{} ends his turn.", player.getName());
		context.fireGameEvent(new TurnEndEvent(context, playerId));
		for (Iterator<CardCostModifier> iterator = context.getCardCostModifiers().iterator(); iterator.hasNext(); ) {
			CardCostModifier cardCostModifier = iterator.next();
			if (cardCostModifier.isExpired()) {
				iterator.remove();
			}
		}
		checkForDeadEntities();
	}

	@Suspendable
	public void equipWeapon(int playerId, Weapon weapon, boolean resolveBattlecry) {
		PreEquipWeapon preEquipWeapon = new PreEquipWeapon(playerId, weapon).invoke();
		Weapon currentWeapon = preEquipWeapon.getCurrentWeapon();
		Player player = preEquipWeapon.getPlayer();

		if (resolveBattlecry
				&& weapon.getBattlecry() != null) {
			resolveBattlecry(playerId, weapon);
		}

		postEquipWeapon(playerId, weapon, currentWeapon, player);
	}

	@Suspendable
	protected void postEquipWeapon(int playerId, Weapon newWeapon, Weapon currentWeapon, Player player) {
		if (currentWeapon != null) {
			log("{} discards currently equipped weapon {}", player.getHero(), currentWeapon);
			destroy(currentWeapon);
			player.getSetAsideZone().remove(currentWeapon);
		}

		player.getStatistics().equipWeapon(newWeapon);
		newWeapon.onEquip(context, player);
		newWeapon.setActive(context.getActivePlayerId() == playerId);
		if (newWeapon.hasSpellTrigger()) {
			List<SpellTrigger> spellTriggers = newWeapon.getSpellTriggers();
			for (SpellTrigger spellTrigger : spellTriggers) {
				addGameEventListener(player, spellTrigger, newWeapon);
			}
		}
		if (newWeapon.getCardCostModifier() != null) {
			addManaModifier(player, newWeapon.getCardCostModifier(), newWeapon);
		}
		checkForDeadEntities();
		context.fireGameEvent(new WeaponEquippedEvent(context, newWeapon));
		context.fireGameEvent(new BoardChangedEvent(context));
	}

	@Suspendable
	public void fight(Player player, Actor attacker, Actor defender) {
		log("{} attacks {}", attacker, defender);

		context.getEnvironment().put(Environment.ATTACKER_REFERENCE, attacker.getReference());

		TargetAcquisitionEvent targetAcquisitionEvent = new TargetAcquisitionEvent(context, player.getId(), ActionType.PHYSICAL_ATTACK,
				attacker, defender);
		context.fireGameEvent(targetAcquisitionEvent);
		Actor target = defender;
		if (context.getEnvironment().containsKey(Environment.TARGET_OVERRIDE)) {
			target = (Actor) context.resolveSingleTarget((EntityReference) context.getEnvironment().get(Environment.TARGET_OVERRIDE));
		}
		context.getEnvironment().remove(Environment.TARGET_OVERRIDE);

		// if (attacker.hasTag(GameTag.FUMBLE) && randomBool()) {
		// log("{} fumbled and hits another target", attacker);
		// target = getAnotherRandomTarget(player, attacker, defender,
		// EntityReference.ENEMY_CHARACTERS);
		// }

		if (target != defender) {
			log("Target of attack was changed! New Target: {}", target);
		}

		if (attacker.hasAttribute(Attribute.IMMUNE_WHILE_ATTACKING)) {
			applyAttribute(attacker, Attribute.IMMUNE);
		}

		removeAttribute(attacker, Attribute.STEALTH);

		int attackerDamage = attacker.getAttack();
		int defenderDamage = target.getAttack();
		context.fireGameEvent(new PhysicalAttackEvent(context, attacker, target, attackerDamage));
		// secret may have killed attacker ADDENDUM: or defender
		if (attacker.isDestroyed() || target.isDestroyed()) {
			context.getEnvironment().remove(Environment.ATTACKER_REFERENCE);
			return;
		}

		if (target.getOwner() == Entity.NO_OWNER) {
			logger.error("Target has no owner!! {}", target);
		}

		Player owningPlayer = context.getPlayer(target.getOwner());
		boolean damaged = damage(owningPlayer, target, attackerDamage, attacker) > 0;
		if (defenderDamage > 0) {
			damage(player, attacker, defenderDamage, target);
		}
		if (attacker.hasAttribute(Attribute.IMMUNE_WHILE_ATTACKING)) {
			attacker.getAttributes().remove(Attribute.IMMUNE);
		}

		if (attacker.getEntityType() == EntityType.HERO) {
			Hero hero = (Hero) attacker;
			Weapon weapon = hero.getWeapon();
			if (weapon != null && weapon.isActive()) {
				modifyDurability(hero.getWeapon(), -1);
			}
		}
		attacker.modifyAttribute(Attribute.NUMBER_OF_ATTACKS, -1);

		context.fireGameEvent(new AfterPhysicalAttackEvent(context, attacker, target, damaged ? attackerDamage : 0));

		context.getEnvironment().remove(Environment.ATTACKER_REFERENCE);
	}

	@Suspendable
	public void gainArmor(Player player, int armor) {
		logger.debug("{} gains {} armor", player.getHero(), armor);
		player.getHero().modifyArmor(armor);
		player.getStatistics().armorGained(armor);
		if (armor > 0) {
			context.fireGameEvent(new ArmorGainedEvent(context, player.getHero()));
		}
	}

	public String generateCardId() {
		return TEMP_CARD_LABEL + idFactory.generateId();
	}

	public Actor getAnotherRandomTarget(Player player, Actor attacker, Actor originalTarget, EntityReference potentialTargets) {
		List<Entity> validTargets = context.resolveTarget(player, null, potentialTargets);
		// cannot redirect to attacker
		validTargets.remove(attacker);
		// cannot redirect to original target
		validTargets.remove(originalTarget);
		if (validTargets.isEmpty()) {
			return originalTarget;
		}

		return (Actor) SpellUtils.getRandomTarget(validTargets);
	}

	/**
	 * Returns the first value of the attribute encountered. This method should
	 * be used with caution, as the result is random if there are different
	 * values of the same attribute in play.
	 *
	 * @param player
	 * @param attr         Which attribute to find
	 * @param defaultValue The value returned if no occurrence of the attribute is found
	 * @return the first occurrence of the value of attribute or defaultValue
	 */
	public int getAttributeValue(Player player, Attribute attr, int defaultValue) {
		for (Entity minion : player.getMinions()) {
			if (minion.hasAttribute(attr)) {
				return minion.getAttributeValue(attr);
			}
		}

		return defaultValue;
	}

	@Suspendable
	public GameAction getAutoHeroPowerAction(int playerId) {
		return actionLogic.getAutoHeroPower(context, context.getPlayer(playerId));
	}

	/**
	 * Return the greatest value of the attribute from all Actors of a Player.
	 * This method will return infinite if an Attribute value is negative, so
	 * use this method with caution.
	 *
	 * @param player Which Player to check
	 * @param attr   Which attribute to find
	 * @return The highest value from all sources. -1 is considered infinite.
	 */
	public int getGreatestAttributeValue(Player player, Attribute attr) {
		int greatest = Math.max(INFINITE, player.getHero().getAttributeValue(attr));
		if (greatest == INFINITE) {
			return greatest;
		}
		for (Entity minion : player.getMinions()) {
			if (minion.hasAttribute(attr)) {
				if (minion.getAttributeValue(attr) > greatest) {
					greatest = minion.getAttributeValue(attr);
				}
				if (minion.getAttributeValue(attr) == INFINITE) {
					return INFINITE;
				}
			}
		}
		return greatest;
	}

	public MatchResult getMatchResult(Player player, Player opponent) {
		boolean playerLost = hasPlayerLost(player);
		boolean opponentLost = hasPlayerLost(opponent);
		if (playerLost && opponentLost) {
			return MatchResult.DOUBLE_LOSS;
		} else if (playerLost || opponentLost) {
			return MatchResult.WON;
		}
		return MatchResult.RUNNING;
	}

	public int getModifiedManaCost(Player player, Card card) {
		int manaCost = card.getManaCost(context, player);
		int minValue = 0;
		for (CardCostModifier costModifier : context.getCardCostModifiers()) {
			if (!costModifier.appliesTo(card)) {
				continue;
			}
			manaCost = costModifier.process(card, manaCost);
			if (costModifier.getMinValue() > minValue) {
				minValue = costModifier.getMinValue();
			}
		}
		if (card.hasAttribute(Attribute.MANA_COST_MODIFIER)) {
			manaCost += card.getAttributeValue(Attribute.MANA_COST_MODIFIER);
		}
		manaCost = MathUtils.clamp(manaCost, minValue, Integer.MAX_VALUE);
		return manaCost;
	}

	public List<IGameEventListener> getSecrets(Player player) {
		List<IGameEventListener> secrets = context.getTriggersAssociatedWith(player.getHero().getReference());
		for (Iterator<IGameEventListener> iterator = secrets.iterator(); iterator.hasNext(); ) {
			IGameEventListener trigger = iterator.next();
			if (!(trigger instanceof Secret)) {
				iterator.remove();
			}
		}
		return secrets;
	}

	public int getTotalAttributeValue(Player player, Attribute attr) {
		int total = player.getHero().getAttributeValue(attr);
		for (Entity minion : player.getMinions()) {
			if (!minion.hasAttribute(attr)) {
				continue;
			}

			total += minion.getAttributeValue(attr);
		}
		return total;
	}

	public int getTotalAttributeMultiplier(Player player, Attribute attribute) {
		int total = 1;
		if (player.getHero().hasAttribute(attribute)) {
			player.getHero().getAttributeValue(attribute);
		}
		for (Entity minion : player.getMinions()) {
			if (minion.hasAttribute(attribute)) {
				total *= minion.getAttributeValue(attribute);
			}
		}
		return total;
	}

	@Suspendable
	public List<GameAction> getValidActions(int playerId) {
		Player player = context.getPlayer(playerId);
		return actionLogic.getValidActions(context, player);
	}

	public List<Entity> getValidTargets(int playerId, GameAction action) {
		Player player = context.getPlayer(playerId);
		return targetLogic.getValidTargets(context, player, action);
	}

	public Player getWinner(Player player, Player opponent) {
		boolean playerLost = hasPlayerLost(player);
		boolean opponentLost = hasPlayerLost(opponent);
		if (playerLost && opponentLost) {
			return null;
		} else if (opponentLost) {
			return player;
		} else if (playerLost) {
			return opponent;
		}
		return null;
	}

	@Suspendable
	private void handleEnrage(Actor entity) {
		if (!entity.hasAttribute(Attribute.ENRAGABLE)) {
			return;
		}
		boolean enraged = entity.getHp() < entity.getMaxHp();
		// enrage publicState has not changed; do nothing
		if (entity.hasAttribute(Attribute.ENRAGED) == enraged) {
			return;
		}

		if (enraged) {
			log("{} is now enraged", entity);
			entity.setAttribute(Attribute.ENRAGED);
		} else {
			log("{} is no longer enraged", entity);
			entity.getAttributes().remove(Attribute.ENRAGED);
		}

		context.fireGameEvent(new EnrageChangedEvent(context, entity));
	}

	@Suspendable
	private void handleFrozen(Actor actor) {
		if (!actor.hasAttribute(Attribute.FROZEN)) {
			return;
		}
		if (actor.getAttributeValue(Attribute.NUMBER_OF_ATTACKS) >= actor.getMaxNumberOfAttacks()) {
			removeAttribute(actor, Attribute.FROZEN);
		}
	}

	public boolean hasAttribute(Player player, Attribute attr) {
		if (player.getHero().hasAttribute(attr)) {
			return true;
		}
		for (Entity minion : player.getMinions()) {
			if (minion.hasAttribute(attr)) {
				return true;
			}
		}

		return false;
	}

	@Suspendable
	public boolean hasAutoHeroPower(int player) {
		return actionLogic.hasAutoHeroPower(context, context.getPlayer(player));
	}

	public boolean hasCard(Player player, Card card) {
		for (Card heldCard : player.getHand()) {
			if (card.getCardId().equals(heldCard.getCardId())) {
				return true;
			}
		}
		if (player.getHero().getHeroPower().getCardId().equals(card.getCardId())) {
			return true;
		}
		return false;
	}

	@Suspendable
	public void heal(Player player, Actor target, int healing, Entity source) {
		if (hasAttribute(player, Attribute.INVERT_HEALING)) {
			log("All healing inverted, deal damage instead!");
			damage(player, target, healing, source);
			return;
		}
		if (source != null && source instanceof Card
				&& (((Card) source).getCardType().isCardType(CardType.SPELL)
				|| ((Card) source).getCardType().isCardType(CardType.HERO_POWER))) {
			healing = applyAmplify(player, healing, Attribute.HEAL_AMPLIFY_MULTIPLIER);
		}
		boolean success = false;
		switch (target.getEntityType()) {
			case MINION:
				success = healMinion((Actor) target, healing);
				break;
			case HERO:
				success = healHero((Hero) target, healing);
				break;
			default:
				break;
		}

		if (success) {
			HealEvent healEvent = new HealEvent(context, player.getId(), target, healing);
			context.fireGameEvent(healEvent);
			player.getStatistics().heal(healing);
		}
	}

	private boolean healHero(Hero hero, int healing) {
		int newHp = Math.min(hero.getMaxHp(), hero.getHp() + healing);
		int oldHp = hero.getHp();
		if (logger.isDebugEnabled()) {
			log(hero + " is healed for " + healing + ", hp now: " + newHp / hero.getMaxHp());
		}

		hero.setHp(newHp);
		return newHp != oldHp;
	}

	@Suspendable
	private boolean healMinion(Actor minion, int healing) {
		int newHp = Math.min(minion.getMaxHp(), minion.getHp() + healing);
		int oldHp = minion.getHp();
		if (logger.isDebugEnabled()) {
			log(minion + " is healed for " + healing + ", hp now: " + newHp + "/" + minion.getMaxHp());
		}

		minion.setHp(newHp);
		handleEnrage(minion);
		return newHp != oldHp;
	}

	@Suspendable
	public void init(int playerId, boolean begins) {
		mulligan(context.getPlayer(playerId), begins);

		startGameForPlayer(context.getPlayer(playerId));
	}

	@Suspendable
	protected void startGameForPlayer(Player player) {
		for (Card card : player.getDeck()) {
			if (card.getAttribute(Attribute.DECK_TRIGGER) != null) {
				TriggerDesc triggerDesc = (TriggerDesc) card.getAttribute(Attribute.DECK_TRIGGER);
				addGameEventListener(player, triggerDesc.create(), card);
			}
		}
		for (Card card : player.getHand()) {
			if (card.getAttribute(Attribute.DECK_TRIGGER) != null) {
				TriggerDesc triggerDesc = (TriggerDesc) card.getAttribute(Attribute.DECK_TRIGGER);
				addGameEventListener(player, triggerDesc.create(), card);
			}
		}

		GameStartEvent gameStartEvent = new GameStartEvent(context, player.getId());
		context.fireGameEvent(gameStartEvent);
	}

	@Suspendable
	public Player initializePlayer(int playerId) {
		Player player = context.getPlayer(playerId);
		player.setOwner(player.getId());
		player.getHero().setId(getIdFactory().generateId());
		player.getHero().setOwner(player.getId());
		player.getHero().setMaxHp(player.getHero().getAttributeValue(Attribute.BASE_HP));
		player.getHero().setHp(player.getHero().getAttributeValue(Attribute.BASE_HP));

		player.getHero().getHeroPower().setId(getIdFactory().generateId());
		assignCardIds(player.getDeck(), playerId);
		assignCardIds(player.getHand(), playerId);

		log("Setting hero hp to {} for {}", player.getHero().getHp(), player.getName());
		player.getDeck().shuffle();
		return player;
	}

	public boolean isLoggingEnabled() {
		return loggingEnabled;
	}

	public JoustEvent joust(Player player) {
		Card ownCard = player.getDeck().getRandomOfType(CardType.MINION);
		Card opponentCard = null;
		boolean won = false;
		// no minions left in deck - automatically loose joust
		if (ownCard == null) {
			won = false;
			log("Jousting LOST - no minion card left");
		} else {
			Player opponent = context.getOpponent(player);
			opponentCard = opponent.getDeck().getRandomOfType(CardType.MINION);
			// opponent has no minions left in deck - automatically win joust
			if (opponentCard == null) {
				won = true;
				log("Jousting WON - opponent has no minion card left");
			} else {
				// both players have minion cards left, the initiator needs to
				// have the one with
				// higher mana cost to win the joust
				won = ownCard.getBaseManaCost() > opponentCard.getBaseManaCost();

				log("Jousting {} - {} vs. {}", won ? "WON" : "LOST", ownCard, opponentCard);
			}
		}
		JoustEvent joustEvent = new JoustEvent(context, player.getId(), won, ownCard, opponentCard);
		context.fireGameEvent(joustEvent);
		return joustEvent;
	}

	private void log(String message) {
		logToDebugHistory(message);
		if (isLoggingEnabled() && logger.isDebugEnabled()) {
			logger.debug(message);
		}
	}

	private void log(String message, Object param1) {
		logToDebugHistory(message, param1);
		if (isLoggingEnabled() && logger.isDebugEnabled()) {
			logger.debug(message, param1);
		}
	}

	protected void log(String message, Object param1, Object param2) {
		logToDebugHistory(message, param1, param2);
		if (isLoggingEnabled() && logger.isDebugEnabled()) {
			logger.debug(message, param1, param2);
		}
	}

	private void log(String message, Object param1, Object param2, Object param3) {
		logToDebugHistory(message, param1, param2, param3);
		if (isLoggingEnabled() && logger.isDebugEnabled()) {
			logger.debug(message, param1, param2, param3);
		}
	}

	private void logToDebugHistory(String message, Object... params) {
		if (!isLoggingEnabled()) {
			return;
		}
		if (debugHistory.size() == MAX_HISTORY_ENTRIES) {
			debugHistory.poll();
		}
		if (params != null && params.length > 0) {
			message = message.replaceAll("\\{\\}", "%s");
			message = String.format(message, params);
		}

		debugHistory.add(message);
	}

	public void markAsDestroyed(Actor target) {
		if (target != null) {
			target.setAttribute(Attribute.DESTROYED);
		}
	}

	@Suspendable
	public void mindControl(Player player, Minion minion) {
		log("{} mind controls {}", player.getName(), minion);
		Player opponent = context.getOpponent(player);
		if (!opponent.getMinions().contains(minion)) {
			// logger.warn("Minion {} cannot be mind-controlled, because
			// opponent does not own it.", minion);
			return;
		}
		if (canSummonMoreMinions(player)) {
			context.getOpponent(player).getMinions().remove(minion);
			player.getMinions().add(minion);
			minion.setOwner(player.getId());
			applyAttribute(minion, Attribute.SUMMONING_SICKNESS);
			refreshAttacksPerRound(minion);
			List<IGameEventListener> triggers = context.getTriggersAssociatedWith(minion.getReference());
			removeSpellTriggers(minion);
			for (IGameEventListener trigger : triggers) {
				addGameEventListener(player, trigger, minion);
			}
			context.fireGameEvent(new BoardChangedEvent(context));
		} else {
			markAsDestroyed(minion);
		}
	}

	public void modifyCurrentMana(int playerId, int mana) {
		Player player = context.getPlayer(playerId);
		int newMana = Math.min(player.getMana() + mana, MAX_MANA);
		player.setMana(newMana);
	}

	public void modifyDurability(Weapon weapon, int durability) {
		log("Durability of weapon {} is changed by {}", weapon, durability);

		weapon.modifyAttribute(Attribute.HP, durability);
		if (durability > 0) {
			weapon.modifyAttribute(Attribute.MAX_HP, durability);
		}
	}

	@Suspendable
	public void modifyMaxHp(Actor actor, int value) {
		actor.setMaxHp(value);
		actor.setHp(value);
		handleEnrage(actor);
	}

	public void modifyMaxMana(Player player, int delta) {
		log("Maximum mana was changed by {} for {}", delta, player.getName());
		int maxMana = MathUtils.clamp(player.getMaxMana() + delta, 0, GameLogic.MAX_MANA);
		player.setMaxMana(maxMana);
		if (delta < 0 && player.getMana() > player.getMaxMana()) {
			modifyCurrentMana(player.getId(), delta);
		}
	}

	@Suspendable
	protected void mulligan(Player player, boolean begins) {
		FirstHand firstHand = new FirstHand(player, begins).invoke();

		List<Card> discardedCards = player.getBehaviour().mulligan(context, player, firstHand.getStarterCards());

		handleMulligan(player, begins, firstHand, discardedCards);
	}

	@Suspendable
	protected void handleMulligan(Player player, boolean begins, FirstHand firstHand, List<Card> discardedCards) {
		// Get the entity ids of the discarded cards and then replace the discarded cards with them
		final Map<Integer, Entity> setAsideZone = player.getSetAsideZone().stream().collect(Collectors.toMap(Entity::getId, Function.identity()));
		discardedCards = discardedCards.stream().map(Card::getId).map(setAsideZone::get).map(e -> (Card) e).collect(Collectors.toList());

		// The starter cards have been put into the setAsideZone
		List<Card> starterCards = firstHand.getStarterCards();
		int numberOfStarterCards = firstHand.getNumberOfStarterCards();

		// remove player selected cards from starter cards
		for (Card discardedCard : discardedCards) {
			log("Player {} mulligans {} ", player.getName(), discardedCard);
			starterCards.removeIf(c -> c.getId() == discardedCard.getId());
		}

		// draw random cards from deck until required starter card count is
		// reached
		while (starterCards.size() < numberOfStarterCards) {
			Card randomCard = player.getDeck().getRandom();
			player.getDeck().move(randomCard, player.getSetAsideZone());
			starterCards.add(randomCard);
		}

		// put the networkRequestMulligan cards back in the deck
		for (Card discardedCard : discardedCards) {
			player.getSetAsideZone().move(discardedCard, player.getDeck());
		}

		for (Card starterCard : starterCards) {
			if (starterCard != null) {
				receiveCard(player.getId(), starterCard);
			}
		}

		player.getDeck().shuffle();

		// second player gets the coin additionally
		if (!begins) {
			Card theCoin = CardCatalogue.getCardById("spell_the_coin");
			receiveCard(player.getId(), theCoin);
		}
	}

	public void panicDump() {
		for (String entry : debugHistory) {
			logger.error(entry);
		}
	}

	@Suspendable
	public void performGameAction(int playerId, GameAction action) {
		getActionStack().push(action);
		context.onWillPerformGameAction(playerId, action);
		if (isLoggingEnabled()) {
			debugHistory.add(action.toString());
		}

		if (playerId != context.getActivePlayerId()) {
			logger.warn("Player {} tries to perform an action, but it is not his turn!", context.getPlayer(playerId).getName());
		}
		if (action.getTargetRequirement() != TargetSelection.NONE) {
			Entity target = context.resolveSingleTarget(action.getTargetKey());
			if (target != null) {
				context.getEnvironment().put(Environment.TARGET, target.getReference());
			} else {
				context.getEnvironment().put(Environment.TARGET, null);
			}
		}

		action.execute(context, playerId);

		context.getEnvironment().remove(Environment.TARGET);
		if (action.getActionType() != ActionType.BATTLECRY) {
			checkForDeadEntities();
		}

		// Calculate how all the entities changed.

		context.onDidPerformGameAction(playerId, action);
		getActionStack().pop();
	}

	@Suspendable
	public void playCard(int playerId, CardReference cardReference) {
		Player player = context.getPlayer(playerId);
		Card card = context.resolveCardReference(cardReference);

		int modifiedManaCost = getModifiedManaCost(player, card);
		if (card.getCardType().isCardType(CardType.SPELL)
				&& player.hasAttribute(Attribute.SPELLS_COST_HEALTH)) {
			context.getEnvironment().put(Environment.LAST_MANA_COST, 0);
			damage(player, player.getHero(), modifiedManaCost, card, true);
		} else if ((Race) card.getAttribute(Attribute.RACE) == Race.MURLOC
				&& player.getHero().hasAttribute(Attribute.MURLOCS_COST_HEALTH)) {
			context.getEnvironment().put(Environment.LAST_MANA_COST, 0);
			damage(player, player.getHero(), modifiedManaCost, card, true);
		} else {
			context.getEnvironment().put(Environment.LAST_MANA_COST, modifiedManaCost);
			modifyCurrentMana(playerId, -modifiedManaCost);
			player.getStatistics().manaSpent(modifiedManaCost);
		}
		log("{} plays {}", player.getName(), card);

		player.getStatistics().cardPlayed(card);
		CardPlayedEvent cardPlayedEvent = new CardPlayedEvent(context, playerId, card);
		context.fireGameEvent(cardPlayedEvent);

		if (card.hasAttribute(Attribute.OVERLOAD)) {
			context.fireGameEvent(new OverloadEvent(context, playerId, card));
		}

		removeCard(playerId, card);

		if ((card.getCardType().isCardType(CardType.SPELL))) {
			GameEvent spellCastedEvent = new SpellCastedEvent(context, playerId, card);
			context.fireGameEvent(spellCastedEvent);
			if (card.hasAttribute(Attribute.COUNTERED)) {
				log("{} was countered!", card.getName());
				return;
			}
		}

		if (card.hasAttribute(Attribute.OVERLOAD)) {
			player.modifyAttribute(Attribute.OVERLOAD, card.getAttributeValue(Attribute.OVERLOAD));
		}
	}

	@Suspendable
	public void playSecret(Player player, Secret secret) {
		playSecret(player, secret, true);
	}

	@Suspendable
	public void playSecret(Player player, Secret secret, boolean fromHand) {
		log("{} has a new secret activated: {}", player.getName(), secret.getSource());
		Secret newSecret = secret.clone();
		newSecret.setId(getIdFactory().generateId());
		newSecret.setOwner(player.getId());
		addGameEventListener(player, newSecret, player.getHero());
		player.getSecrets().add(newSecret);
		if (fromHand) {
			context.fireGameEvent(new SecretPlayedEvent(context, player.getId(), (SecretCard) newSecret.getSource()));
		}
	}

	public void processTargetModifiers(Player player, GameAction action) {
		HeroPowerCard heroPower = player.getHero().getHeroPower();
		if (heroPower.getHeroClass() != HeroClass.HUNTER) {
			return;
		}
		if (action.getActionType() == ActionType.HERO_POWER && hasAttribute(player, Attribute.HERO_POWER_CAN_TARGET_MINIONS)) {
			PlaySpellCardAction spellCardAction = (PlaySpellCardAction) action;
			SpellDesc targetChangedSpell = spellCardAction.getSpell().removeArg(SpellArg.TARGET);
			spellCardAction.setSpell(targetChangedSpell);
			spellCardAction.setTargetRequirement(TargetSelection.ANY);
		}
	}

	/**
	 * @param max Upper bound of random number (exclusive)
	 * @return Random number between 0 and max (exclusive)
	 */
	public int random(int max) {
		return getRandom().nextInt(max);
	}

	public boolean randomBool() {
		return getRandom().nextBoolean();
	}

	@Suspendable
	public void receiveCard(int playerId, Card card) {
		receiveCard(playerId, card, null);
	}

	@Suspendable
	public void receiveCard(int playerId, Card card, int copies) {
		for (int i = 0; i < Math.min(copies, 1); i++) {
			receiveCard(playerId, card, null);
		}

		for (int i = 1; i < copies; i++) {
			receiveCard(playerId, card.getCopy(), null);
		}
	}

	@Suspendable
	public void receiveCard(int playerId, Card card, Entity source) {
		receiveCard(playerId, card, source, false);
	}

	@Suspendable
	public void receiveCard(int playerId, Card card, Entity source, boolean drawn) {
		Player player = context.getPlayer(playerId);
		if (card.getId() == IdFactory.UNASSIGNED) {
			card.setId(getIdFactory().generateId());
		}

		card.setOwner(playerId);
		CardZone hand = player.getHand();

		if (hand.getCount() < MAX_HAND_CARDS) {
			if (card.getAttribute(Attribute.PASSIVE_TRIGGER) != null) {
				TriggerDesc triggerDesc = (TriggerDesc) card.getAttribute(Attribute.PASSIVE_TRIGGER);
				addGameEventListener(player, triggerDesc.create(), card);
			}

			log("{} receives card {}", player.getName(), card);
			card.moveOrAddTo(context, Zones.HAND);
			CardType sourceType = null;
			if (source instanceof Card) {
				Card sourceCard = (Card) source;
				sourceType = sourceCard.getCardType();
			}
			context.fireGameEvent(new DrawCardEvent(context, playerId, card, sourceType, drawn));
		} else {
			log("{} has too many cards on his hand, card destroyed: {}", player.getName(), card);
			discardCard(player, card);
		}
	}

	public void refreshAttacksPerRound(Entity entity) {
		int attacks = 1;
		if (entity.hasAttribute(Attribute.MEGA_WINDFURY)) {
			attacks = MEGA_WINDFURY_ATTACKS;
		} else if (entity.hasAttribute(Attribute.WINDFURY)) {
			attacks = WINDFURY_ATTACKS;
		}
		entity.setAttribute(Attribute.NUMBER_OF_ATTACKS, attacks);
	}

	public void removeAttribute(Entity entity, Attribute attr) {
		if (!entity.hasAttribute(attr)) {
			return;
		}
		if (attr == Attribute.MEGA_WINDFURY && entity.hasAttribute(Attribute.WINDFURY)) {
			entity.modifyAttribute(Attribute.NUMBER_OF_ATTACKS, WINDFURY_ATTACKS - MEGA_WINDFURY_ATTACKS);
		}
		if (attr == Attribute.WINDFURY && !entity.hasAttribute(Attribute.MEGA_WINDFURY)) {
			entity.modifyAttribute(Attribute.NUMBER_OF_ATTACKS, 1 - WINDFURY_ATTACKS);
		} else if (attr == Attribute.MEGA_WINDFURY) {
			entity.modifyAttribute(Attribute.NUMBER_OF_ATTACKS, 1 - MEGA_WINDFURY_ATTACKS);
		}
		entity.getAttributes().remove(attr);
		log("Removing attribute {} from {}", attr, entity);
	}

	@Suspendable
	public void removeCard(int playerId, Card card) {
		log("Card {} has been moved from the {} to the GRAVEYARD", card, card.getEntityLocation().getZone().toString());
		removeSpellTriggers(card);
		// If it's already in the graveyard, do nothing
		if (card.getEntityLocation().getZone() == Zones.GRAVEYARD) {
			return;
		}
		// TODO: It's not necessarily in the hand when it's removed!
		card.moveOrAddTo(context, Zones.GRAVEYARD);
	}

	@Suspendable
	public void removeCardFromDeck(int playerID, Card card) {
		Player player = context.getPlayer(playerID);
		log("Card {} has been moved from the DECK to the GRAVEYARD", card);
		removeSpellTriggers(card);
		player.getDeck().move(card, player.getGraveyard());
	}

	@Suspendable
	public void removeMinion(Minion minion, boolean peacefully) {
		removeSpellTriggers(minion);
		log("{} was removed", minion);
		minion.setAttribute(Attribute.DESTROYED);
		minion.moveOrAddTo(context, peacefully ? Zones.SET_ASIDE_ZONE : Zones.GRAVEYARD);
		context.fireGameEvent(new BoardChangedEvent(context));
	}

	@Suspendable
	public void removeSecrets(Player player) {
		log("All secrets for {} have been destroyed", player.getName());
		// this only works while Secrets are the only SpellTrigger on the heroes
		for (IGameEventListener secret : getSecrets(player)) {
			secret.onRemove(context);
			context.removeTrigger(secret);
		}
		player.getSecrets().clear();
	}

	@Suspendable
	private void removeSpellTriggers(Entity entity) {
		removeSpellTriggers(entity, true);
	}

	@Suspendable
	private void removeSpellTriggers(Entity entity, boolean removeAuras) {
		EntityReference entityReference = entity.getReference();
		for (IGameEventListener trigger : context.getTriggersAssociatedWith(entityReference)) {
			if (!removeAuras && trigger instanceof Aura) {
				continue;
			}
			log("SpellTrigger {} was removed for {}", trigger, entity);
			trigger.onRemove(context);
		}
		context.removeTriggersAssociatedWith(entityReference, removeAuras);
		for (Iterator<CardCostModifier> iterator = context.getCardCostModifiers().iterator(); iterator.hasNext(); ) {
			CardCostModifier cardCostModifier = iterator.next();
			if (cardCostModifier.getHostReference().equals(entityReference)) {
				iterator.remove();
			}
		}
	}

	@Suspendable
	public void replaceCard(int playerId, Card oldCard, Card newCard) {
		Player player = context.getPlayer(playerId);
		if (newCard.getId() == IdFactory.UNASSIGNED) {
			newCard.setId(getIdFactory().generateId());
		}

		if (!player.getHand().contains(oldCard)) {
			return;
		}

		newCard.setOwner(playerId);
		CardList hand = player.getHand();

		if (newCard.getAttribute(Attribute.PASSIVE_TRIGGER) != null) {
			TriggerDesc triggerDesc = (TriggerDesc) newCard.getAttribute(Attribute.PASSIVE_TRIGGER);
			addGameEventListener(player, triggerDesc.create(), newCard);
		}

		log("{} replaces card {} with card {}", player.getName(), oldCard, newCard);
		hand.replace(oldCard, newCard);
		removeCard(playerId, oldCard);
		context.fireGameEvent(new DrawCardEvent(context, playerId, newCard, null, false));
	}

	@Suspendable
	public void replaceCardInDeck(int playerId, Card oldCard, Card newCard) {
		Player player = context.getPlayer(playerId);
		if (newCard.getId() == IdFactory.UNASSIGNED) {
			newCard.setId(getIdFactory().generateId());
		}

		if (!player.getDeck().contains(oldCard)) {
			return;
		}

		newCard.setOwner(playerId);
		CardList deck = player.getDeck();

		if (newCard.getAttribute(Attribute.DECK_TRIGGER) != null) {
			TriggerDesc triggerDesc = (TriggerDesc) newCard.getAttribute(Attribute.DECK_TRIGGER);
			addGameEventListener(player, triggerDesc.create(), newCard);
		}

		log("{} replaces card {} with card {}", player.getName(), oldCard, newCard);
		deck.replace(oldCard, newCard);
		removeCardFromDeck(playerId, oldCard);
	}

	@Suspendable
	protected void resolveBattlecry(int playerId, Actor actor) {
		BattlecryAction battlecry = actor.getBattlecry();

		Player player = context.getPlayer(playerId);
		if (!battlecry.canBeExecuted(context, player)) {
			return;
		}

		battlecry.setSource(actor.getReference());

		if (battlecry.getTargetRequirement() != TargetSelection.NONE) {
			List<GameAction> battlecryActions = getTargetedBattlecryGameActions(battlecry, player);

			if (battlecryActions == null
					|| battlecryActions.size() == 0) {
				return;
			}

			BattlecryAction targetedBattlecry = (BattlecryAction) player.getBehaviour().requestAction(context, player, battlecryActions);
			performBattlecryAction(playerId, actor, player, targetedBattlecry);
		} else {
			performBattlecryAction(playerId, actor, player, battlecry);
		}
	}

	protected List<GameAction> getTargetedBattlecryGameActions(BattlecryAction battlecry, Player player) {
		List<Entity> validTargets = targetLogic.getValidTargets(context, player, battlecry);
		if (validTargets.isEmpty()) {
			return null;
		}

		List<GameAction> battlecryActions = new ArrayList<>();
		for (Entity validTarget : validTargets) {
			GameAction targetedBattlecry = battlecry.clone();
			targetedBattlecry.setTarget(validTarget);
			battlecryActions.add(targetedBattlecry);
		}
		return battlecryActions;
	}

	@Suspendable
	protected void performBattlecryAction(int playerId, Actor actor, Player player, BattlecryAction battlecryAction) {
		if (hasAttribute(player, Attribute.DOUBLE_BATTLECRIES) && actor.getSourceCard().hasAttribute(Attribute.BATTLECRY)) {
			// You need DOUBLE_BATTLECRIES before your battlecry action, not after.
			performGameAction(playerId, battlecryAction);
			// Make sure the battlecry is still targetable
			final EntityReference target = battlecryAction.getPredefinedSpellTargetOrUserTarget();
			final boolean targetable = target == null
					|| target.isTargetGroup()
					|| getValidTargets(playerId, battlecryAction).stream().map(EntityReference::pointTo).anyMatch(er -> er.equals(target));
			if (!battlecryAction.canBeExecuted(context, player) || !targetable) {
				return;
			}
			performGameAction(playerId, battlecryAction);
		} else {
			performGameAction(playerId, battlecryAction);
		}
	}

	@Suspendable
	public void resolveDeathrattles(Player player, Actor actor) {
		resolveDeathrattles(player, actor, -1);
	}

	@Suspendable
	public void resolveDeathrattles(Player player, Actor actor, int boardPosition) {
		if (!actor.hasAttribute(Attribute.DEATHRATTLES)) {
			return;
		}
		if (boardPosition == -1) {
			player.getMinions().indexOf(actor);
		}
		boolean doubleDeathrattles = hasAttribute(player, Attribute.DOUBLE_DEATHRATTLES);
		EntityReference sourceReference = actor.getReference();
		for (SpellDesc deathrattleTemplate : actor.getDeathrattles()) {
			SpellDesc deathrattle = deathrattleTemplate.addArg(SpellArg.BOARD_POSITION_ABSOLUTE, boardPosition);
			castSpell(player.getId(), deathrattle, sourceReference, EntityReference.NONE, false);
			if (doubleDeathrattles) {
				// TODO: Likewise, with double deathrattles, make sure that we can still target whatever we're targeting in the spells (possibly metaspells!)
				castSpell(player.getId(), deathrattle, sourceReference, EntityReference.NONE, false);
			}
		}
	}

	@Suspendable
	public void secretTriggered(Player player, Secret secret) {
		log("Secret was trigged: {}", secret.getSource());
		// Move the secret to removed from play.
		secret.moveOrAddTo(context, Zones.REMOVED_FROM_PLAY);
		context.fireGameEvent(new SecretRevealedEvent(context, (SecretCard) secret.getSource(), player.getId()));
	}

	// TODO: circular dependency. Very ugly, refactor!
	public void setContext(GameContext context) {
		this.context = context;
	}

	public void setLoggingEnabled(boolean loggingEnabled) {
		this.loggingEnabled = loggingEnabled;
	}

	@Suspendable
	public void shuffleToDeck(Player player, Card card) {
		if (card.getId() == IdFactory.UNASSIGNED) {
			card.setId(getIdFactory().generateId());
		}

		if (player.getDeck().getCount() < MAX_DECK_SIZE) {
			player.getDeck().addRandomly(card);

			if (card.getAttribute(Attribute.DECK_TRIGGER) != null) {
				TriggerDesc triggerDesc = (TriggerDesc) card.getAttribute(Attribute.DECK_TRIGGER);
				addGameEventListener(player, triggerDesc.create(), card);
			}
			log("Card {} has been shuffled to {}'s deck", card, player.getName());
		}
	}

	/**
	 * Silence is an ability which removes all current card text, enchantments, and abilities from the targeted minion.
	 * It does not remove damage or minion type.
	 *
	 * @param playerId The ID of the player (typically the owner of the target). This is used by {@link
	 *                 net.demilich.metastone.game.spells.custom.ShadowMadnessSpell} to reverse the mind control of a
	 *                 minion that somehow gets silenced during the turn that spell is cast.
	 * @param target   A {@link Minion} to silence.
	 * @see <a href="http://hearthstone.gamepedia.com/Silence">Silence</a> for a complete description of the silencing
	 * game rules.
	 */
	@Suspendable
	public void silence(int playerId, Minion target) {
		context.fireGameEvent(new SilenceEvent(context, playerId, target));

		List<Attribute> tags = new ArrayList<Attribute>();
		tags.addAll(target.getAttributes().keySet());
		for (Attribute attr : tags) {
			if (immuneToSilence.contains(attr)) {
				continue;
			}
			removeAttribute(target, attr);
		}
		removeSpellTriggers(target);
		target.setAttribute(Attribute.SILENCED);

		int oldMaxHp = target.getMaxHp();
		target.setMaxHp(target.getAttributeValue(Attribute.BASE_HP));
		target.setAttack(target.getAttributeValue(Attribute.BASE_ATTACK));
		if (target.getHp() > target.getMaxHp()) {
			target.setHp(target.getMaxHp());
		} else if (oldMaxHp < target.getMaxHp()) {
			target.setHp(target.getHp() + target.getMaxHp() - oldMaxHp);
		}

		log("{} was silenced", target);
	}

	@Suspendable
	public void startTurn(int playerId) {
		Player player = context.getPlayer(playerId);
		if (player.getMaxMana() < MAX_MANA) {
			player.setMaxMana(player.getMaxMana() + 1);
		}
		player.getStatistics().startTurn();

		player.setLockedMana(player.getAttributeValue(Attribute.OVERLOAD));
		int mana = Math.min(player.getMaxMana() - player.getLockedMana(), MAX_MANA);
		player.setMana(mana);
		String manaString = player.getMana() + "/" + player.getMaxMana();
		if (player.getLockedMana() > 0) {
			manaString += " (" + player.getLockedMana() + " locked by overload)";
		}
		log("{} starts his turn with {} mana", player.getName(), manaString);

		player.getAttributes().remove(Attribute.OVERLOAD);
		for (Minion minion : player.getMinions()) {
			minion.getAttributes().remove(Attribute.TEMPORARY_ATTACK_BONUS);
		}

		player.getHero().getHeroPower().setUsed(0);
		player.getHero().activateWeapon(true);
		refreshAttacksPerRound(player.getHero());
		for (Entity minion : player.getMinions()) {
			minion.getAttributes().remove(Attribute.SUMMONING_SICKNESS);
			refreshAttacksPerRound(minion);
		}
		context.fireGameEvent(new TurnStartEvent(context, player.getId()));
		drawCard(playerId, null);
		checkForDeadEntities();
	}

	@Suspendable
	public boolean summon(int playerId, Minion minion, Card source, int index, boolean resolveBattlecry) {
		PreSummon preSummon = new PreSummon(playerId, minion, index, source).invoke();
		if (preSummon.failed()) return false;
		Player player = preSummon.getPlayer();

		if (resolveBattlecry && minion.getBattlecry() != null) {
			resolveBattlecry(player.getId(), minion);
			checkForDeadEntities();
		}

		postSummon(minion, source, player);
		return true;
	}

	@Suspendable
	protected void postSummon(Minion minion, Card source, Player player) {
		if (context.getEnvironment().get(Environment.TRANSFORM_REFERENCE) != null) {
			minion = (Minion) context.resolveSingleTarget((EntityReference) context.getEnvironment().get(Environment.TRANSFORM_REFERENCE));
			minion.setBattlecry(null);
			context.getEnvironment().remove(Environment.TRANSFORM_REFERENCE);
		}

		context.fireGameEvent(new BoardChangedEvent(context));

		player.getStatistics().minionSummoned(minion);
		if (context.getEnvironment().get(Environment.TARGET_OVERRIDE) != null) {
			Actor actor = (Actor) context.resolveSingleTarget((EntityReference) context.getEnvironment().get(Environment.TARGET_OVERRIDE));
			context.getEnvironment().remove(Environment.TARGET_OVERRIDE);
			SummonEvent summonEvent = new SummonEvent(context, actor, source);
			context.fireGameEvent(summonEvent);
		} else {
			SummonEvent summonEvent = new SummonEvent(context, minion, source);
			context.fireGameEvent(summonEvent);
		}

		applyAttribute(minion, Attribute.SUMMONING_SICKNESS);
		refreshAttacksPerRound(minion);

		if (minion.hasSpellTrigger()) {
			for (SpellTrigger trigger : minion.getSpellTriggers()) {
				addGameEventListener(player, trigger, minion);
			}
		}

		if (minion.getCardCostModifier() != null) {
			addManaModifier(player, minion.getCardCostModifier(), minion);
		}

		if (source != null) {
			source.setAttribute(Attribute.ATTACK, source.getAttributeValue(Attribute.BASE_ATTACK));
			source.setAttribute(Attribute.ATTACK_BONUS, 0);
			source.setAttribute(Attribute.MAX_HP, source.getAttributeValue(Attribute.BASE_HP));
			source.setAttribute(Attribute.HP, source.getAttributeValue(Attribute.BASE_HP));
			source.setAttribute(Attribute.HP_BONUS, 0);
		}
		handleEnrage(minion);

		context.getSummonReferenceStack().pop();
		if (player.getMinions().contains(minion)) {
			context.fireGameEvent(new AfterSummonEvent(context, minion, source));
		}
		context.fireGameEvent(new BoardChangedEvent(context));
	}

	/**
	 * Transforms a Minion into a new Minion.
	 *
	 * @param minion    The original minion in play
	 * @param newMinion The new minion to transform into
	 */
	@Suspendable
	public void transformMinion(Minion minion, Minion newMinion) {
		// Remove any spell triggers associated with the old minion.
		removeSpellTriggers(minion);

		Player owner = context.getPlayer(minion.getOwner());
		int index = -1;
		if (!minion.getEntityLocation().equals(EntityLocation.UNASSIGNED)
				&& owner != null) {
			index = minion.getEntityLocation().getIndex();
			owner.getZone(minion.getEntityLocation().getZone()).remove(index);
		}

		// If we want to straight up remove a minion from existence without
		// killing it, this would be the best way.
		if (newMinion != null) {
			log("{} was transformed to {}", minion, newMinion);

			// Give the new minion an ID.
			newMinion.setId(getIdFactory().generateId());
			newMinion.setOwner(owner.getId());

			// If the minion being transforms is being summoned, replace the old
			// minion on the stack.
			// Otherwise, summon the add the new minion.
			// However, do not give a summon event.
			if (!context.getSummonReferenceStack().isEmpty() && context.getSummonReferenceStack().peek().equals(minion.getReference())
					&& !context.getEnvironment().containsKey(Environment.TRANSFORM_REFERENCE)) {
				context.getEnvironment().put(Environment.TRANSFORM_REFERENCE, newMinion.getReference());
				owner.getMinions().add(index, newMinion);

				// It's quite possible that this is actually supposed to add the
				// minion to the zone it was originally in.
				// This means minions in the SetAsideZone or the Graveyard that are
				// targeted (through bizarre mechanics)
				// add the minion to there. This will be tested eventually with
				// Resurrect, Recombobulator, and Illidan.
				// Since this is unknown, this is the patch for it.
			} else if (!owner.getSetAsideZone().contains(minion)) {
				if (index < 0 || index >= owner.getMinions().size()) {
					owner.getMinions().add(newMinion);
				} else {
					owner.getMinions().add(index, newMinion);
				}

				applyAttribute(newMinion, Attribute.SUMMONING_SICKNESS);
				refreshAttacksPerRound(newMinion);

				if (newMinion.hasSpellTrigger()) {
					for (SpellTrigger spellTrigger : newMinion.getSpellTriggers()) {
						addGameEventListener(owner, spellTrigger, newMinion);
					}
				}

				if (newMinion.getCardCostModifier() != null) {
					addManaModifier(owner, newMinion.getCardCostModifier(), newMinion);
				}

				handleEnrage(newMinion);
			} else {
				owner.getSetAsideZone().add(newMinion);
				removeSpellTriggers(newMinion);
				return;
			}

		}

		// Move the old minion to the Set Aside Zone
		owner.getSetAsideZone().add(minion);

		context.fireGameEvent(new BoardChangedEvent(context));
	}

	@Suspendable
	public void useHeroPower(int playerId) {
		Player player = context.getPlayer(playerId);
		HeroPowerCard power = player.getHero().getHeroPower();
		int modifiedManaCost = getModifiedManaCost(player, power);
		modifyCurrentMana(playerId, -modifiedManaCost);
		log("{} uses {}", player.getName(), power);
		power.markUsed();
		player.getStatistics().cardPlayed(power);
		context.fireGameEvent(new HeroPowerUsedEvent(context, playerId, power));
	}

	protected void mulliganAsync(Player player, boolean begins, Handler<Object> callback) {
		throw new RecoverableGameException("Cannot call GameLogic::mulliganAsync from a non-async GameLogic instance.", context);
	}

	public void initAsync(int playerId, boolean begins, Handler<Player> callback) {
		throw new RecoverableGameException("Cannot call GameLogic::initAsync from a non-async GameLogic instance.", context);

	}

	@Suspendable
	protected void resolveBattlecryAsync(int playerId, Actor actor, Handler<AsyncResult<Boolean>> result) {
		throw new RecoverableGameException("Cannot call GameLogic::resolveBattlecryAsync from a non-async GameLogic instance.", context);

	}

	@Suspendable
	public void equipWeaponAsync(int playerId, Weapon weapon, boolean resolveBattlecry, Handler<AsyncResult<Boolean>> result) {
		throw new RecoverableGameException("Cannot call GameLogic::equipWeaponAsync from a non-async GameLogic instance.", context);
	}

	@Suspendable
	protected void summonAsync(int playerId, Minion minion, Card source, int index, boolean resolveBattlecry, Handler<AsyncResult<Boolean>> summoned) {
		throw new RecoverableGameException("Cannot call GameLogic::summonAsync from a non-async GameLogic instance.", context);
	}

	public Random getRandom() {
		return random;
	}

	public IdFactory getIdFactory() {
		return idFactory;
	}

	public void setIdFactory(IdFactory idFactory) {
		this.idFactory = idFactory;
	}

	public Stack<GameAction> getActionStack() {
		return context.getActionStack();
	}

	protected class FirstHand {
		private Player player;
		private boolean begins;
		private int numberOfStarterCards;
		private List<Card> starterCards;

		public FirstHand(Player player, boolean begins) {
			this.player = player;
			this.begins = begins;
		}

		public int getNumberOfStarterCards() {
			return numberOfStarterCards;
		}

		public List<Card> getStarterCards() {
			return starterCards;
		}

		public FirstHand invoke() {
			numberOfStarterCards = begins ? STARTER_CARDS : STARTER_CARDS + 1;
			starterCards = new ArrayList<>();
			for (int j = 0; j < numberOfStarterCards; j++) {
				Card randomCard = player.getDeck().getRandom();
				if (randomCard != null) {
					player.getDeck().move(randomCard, player.getSetAsideZone());
					log("Player {} been offered card {} for networkRequestMulligan", player.getName(), randomCard);
					starterCards.add(randomCard);
				}
			}
			return this;
		}
	}

	protected class PreEquipWeapon {
		private int playerId;
		private Weapon weapon;
		private Player player;
		private Weapon currentWeapon;

		public PreEquipWeapon(int playerId, Weapon weapon) {
			this.playerId = playerId;
			this.weapon = weapon;
		}

		public Player getPlayer() {
			return player;
		}

		public Weapon getCurrentWeapon() {
			return currentWeapon;
		}

		public PreEquipWeapon invoke() {
			player = context.getPlayer(playerId);

			weapon.setId(getIdFactory().generateId());
			currentWeapon = player.getHero().getWeapon();

			if (currentWeapon != null) {
				player.getWeaponZone().remove(currentWeapon);
				player.getSetAsideZone().add(currentWeapon);
			}

			log("{} equips weapon {}", player.getHero(), weapon);
			player.getHero().setWeapon(weapon);
			return this;
		}
	}

	protected class PreSummon {
		private Card source;
		private boolean failed;
		private int playerId;
		private Minion minion;
		private int index;
		private Player player;

		public PreSummon(int playerId, Minion minion, int index, Card source) {
			this.playerId = playerId;
			this.minion = minion;
			this.index = index;
			this.source = source;
		}

		public boolean failed() {
			return failed;
		}

		public Player getPlayer() {
			return player;
		}

		@Suspendable
		public PreSummon invoke() {
			player = context.getPlayer(playerId);
			if (!canSummonMoreMinions(player)) {
				log("{} cannot summon any more minions, {} is destroyed", player.getName(), minion);
				failed = true;
				return this;
			}
			minion.setId(getIdFactory().generateId());
			minion.setOwner(player.getId());

			context.getSummonReferenceStack().push(minion.getReference());

			log("{} summons {}", player.getName(), minion);

			if (index < 0 || index >= player.getMinions().size()) {
				minion.moveOrAddTo(context, Zones.BATTLEFIELD);
			} else {
				player.getMinions().add(index, minion);
			}

			context.fireGameEvent(new BeforeSummonEvent(context, minion, source));
			context.fireGameEvent(new BoardChangedEvent(context));
			failed = false;
			return this;
		}
	}

	private static boolean hasPlayerLost(Player player) {
		return player.getHero() == null
				|| player.getHero().getHp() < 1
				|| player.getHero().hasAttribute(Attribute.DESTROYED)
				|| player.hasAttribute(Attribute.DESTROYED);
	}
}
