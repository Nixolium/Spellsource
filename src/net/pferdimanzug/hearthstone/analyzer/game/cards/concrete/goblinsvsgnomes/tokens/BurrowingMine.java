package net.pferdimanzug.hearthstone.analyzer.game.cards.concrete.goblinsvsgnomes.tokens;

import net.pferdimanzug.hearthstone.analyzer.game.GameContext;
import net.pferdimanzug.hearthstone.analyzer.game.cards.Rarity;
import net.pferdimanzug.hearthstone.analyzer.game.cards.SpellCard;
import net.pferdimanzug.hearthstone.analyzer.game.entities.Entity;
import net.pferdimanzug.hearthstone.analyzer.game.entities.heroes.HeroClass;
import net.pferdimanzug.hearthstone.analyzer.game.events.DrawCardEvent;
import net.pferdimanzug.hearthstone.analyzer.game.events.GameEvent;
import net.pferdimanzug.hearthstone.analyzer.game.events.GameEventType;
import net.pferdimanzug.hearthstone.analyzer.game.spells.DamageSpell;
import net.pferdimanzug.hearthstone.analyzer.game.spells.DrawCardSpell;
import net.pferdimanzug.hearthstone.analyzer.game.spells.MetaSpell;
import net.pferdimanzug.hearthstone.analyzer.game.spells.NullSpell;
import net.pferdimanzug.hearthstone.analyzer.game.spells.desc.SpellDesc;
import net.pferdimanzug.hearthstone.analyzer.game.spells.trigger.IGameEventListener;
import net.pferdimanzug.hearthstone.analyzer.game.spells.trigger.TriggerLayer;
import net.pferdimanzug.hearthstone.analyzer.game.targeting.EntityReference;
import net.pferdimanzug.hearthstone.analyzer.game.targeting.TargetSelection;

public class BurrowingMine extends SpellCard implements IGameEventListener {

	private EntityReference hostReference;
	private boolean fired;

	public BurrowingMine() {
		super("Burrowing Mine", Rarity.FREE, HeroClass.ANY, 0);
		setDescription("When you draw this, it explodes. You take 10 damage and draw a card.");
		setCollectible(false);

		setSpell(NullSpell.create());
		setTargetRequirement(TargetSelection.NONE);
	}

	@Override
	public BurrowingMine clone() {
		BurrowingMine clone = (BurrowingMine) super.clone();
		clone.hostReference = hostReference != null ? new EntityReference(hostReference.getId()) : null;
		return clone;
	}

	@Override
	public EntityReference getHostReference() {
		return hostReference;
	}

	@Override
	public TriggerLayer getLayer() {
		return TriggerLayer.DEFAULT;
	}

	@Override
	public int getTypeId() {
		return 591;
	}

	@Override
	public boolean interestedIn(GameEventType eventType) {
		return eventType == GameEventType.DRAW_CARD;
	}

	@Override
	public boolean isExpired() {
		return fired;
	}

	@Override
	public void onAdd(GameContext context) {
		fired = false;
	}

	@Override
	public void onGameEvent(GameEvent event) {
		DrawCardEvent cardEvent = (DrawCardEvent) event;
		if (cardEvent.getCard() != this) {
			return;
		}

		fired = true;
		SpellDesc damage = DamageSpell.create(10);
		damage.setTarget(EntityReference.FRIENDLY_HERO);
		setSpell(MetaSpell.create(damage, DrawCardSpell.create()));
		event.getGameContext().getLogic().castSpell(cardEvent.getPlayerId(), damage);
	}

	@Override
	public void onRemove(GameContext context) {
	}

	@Override
	public void setHost(Entity host) {
		this.hostReference = host.getReference();
	}
}
