package net.demilich.metastone.game.spells.trigger;

import net.demilich.metastone.game.events.GameEvent;
import net.demilich.metastone.game.events.ShuffledEvent;
import net.demilich.metastone.game.spells.desc.trigger.EventTriggerDesc;

public class ShuffledOnlyOriginalCopiesTrigger extends ShuffledTrigger {

	public ShuffledOnlyOriginalCopiesTrigger(EventTriggerDesc desc) {
		super(desc);
	}

	@Override
	public boolean fires(GameEvent event) {
		ShuffledEvent shuffledEvent = (ShuffledEvent) event;
		if (shuffledEvent.isExtraCopy()) {
			return false;
		}
		return super.fires(event);
	}
}
