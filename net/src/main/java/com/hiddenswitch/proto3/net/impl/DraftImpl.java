package com.hiddenswitch.proto3.net.impl;

import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.fibers.Suspendable;
import com.hiddenswitch.proto3.draft.DraftContext;
import com.hiddenswitch.proto3.draft.DraftStatus;
import com.hiddenswitch.proto3.draft.PrivateDraftState;
import com.hiddenswitch.proto3.draft.PublicDraftState;
import com.hiddenswitch.proto3.net.Accounts;
import com.hiddenswitch.proto3.net.Decks;
import com.hiddenswitch.proto3.net.Draft;
import com.hiddenswitch.proto3.net.impl.util.DraftRecord;
import com.hiddenswitch.proto3.net.impl.util.UserRecord;
import com.hiddenswitch.proto3.net.models.*;
import com.hiddenswitch.proto3.net.util.RPC;
import com.hiddenswitch.proto3.net.util.RpcClient;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.ext.mongo.UpdateOptions;

import static com.hiddenswitch.proto3.net.util.Mongo.mongo;
import static com.hiddenswitch.proto3.net.util.QuickJson.json;

public class DraftImpl extends AbstractService<DraftImpl> implements Draft {
	private RpcClient<Decks> decks;
	private RpcClient<Accounts> accounts;

	@Override
	@Suspendable
	public void start() throws SuspendExecution {
		super.start();
		decks = RPC.connect(Decks.class, vertx.eventBus());
		accounts = RPC.connect(Accounts.class, vertx.eventBus());
		// Create the collection if necessary
		if (!mongo().getCollections().contains(DRAFTS)) {
			mongo().createCollection(DRAFTS);
		}
		RPC.register(this, Draft.class, vertx.eventBus());
	}

	@Override
	@Suspendable
	public DraftRecord get(GetDraftRequest request) {
		return getRecord(request.userId);
	}

	@Suspendable
	private DraftRecord getRecord(String userId) {
		return mongo().findOne(DRAFTS, json("_id", userId), DraftRecord.class);
	}

	@Override
	@Suspendable
	public DraftRecord doDraftAction(DraftActionRequest request) throws SuspendExecution, InterruptedException, NullPointerException {
		DraftRecord record = getRecord(request.getUserId());

		if (record == null) {
			// Assume that the player is starting a draft for the first time

			// TODO: Deduct lives.
			record = new DraftRecord();
			record.setPublicDraftState(new PublicDraftState());
			record.setPrivateDraftState(new PrivateDraftState());
		}

		DraftContext context = new DraftContext()
				.withPrivateState(record.getPrivateDraftState())
				.withPublicState(record.getPublicDraftState());

		switch (context.getPublicState().getStatus()) {
			case NOT_STARTED:
				context.accept(null);
				break;
			case SELECT_HERO:
				if (request.getHeroIndex() == -1) {
					throw new NullPointerException("No hero index was provided.");
				}

				context.onHeroSelected(Future.succeededFuture(record.getPublicDraftState().getHeroClassChoices().get(request.getHeroIndex())));
				break;
			case IN_PROGRESS:
				if (request.getCardIndex() == -1) {
					throw new NullPointerException("No card index was provided.");
				}

				context.onCardSelected(Future.succeededFuture(request.getCardIndex()));
				break;
			case COMPLETE:
				break;
		}

		// If the draft is now complete, create a deck
		if (record.getPublicDraftState().getDeckId() == null
				&& record.getPublicDraftState().getStatus() == DraftStatus.COMPLETE) {
			UserRecord user = accounts.sync().get(request.getUserId());

			DeckCreateResponse deck = decks.sync().createDeck(
					new DeckCreateRequest()
							.withName(String.format("%s's Draft Deck", user.getProfile().getDisplayName()))
							.withHeroClass(record.getPublicDraftState().getHeroClass())
							.withUserId(request.getUserId())
							.withCardIds(record.getPublicDraftState().getCurrentCardChoices()));

			record.getPublicDraftState().setDeckId(deck.getDeckId());
		}

		// For now just do a big upsert.
		mongo().updateCollectionWithOptions(DRAFTS, json("_id", request.getUserId()), json("$set", json(record)), new UpdateOptions().setUpsert(true));

		return record;
	}

	@Override
	@Suspendable
	public MatchDraftResponse matchDraft(MatchDraftRequest request) {
		return null;
	}

	@Override
	@Suspendable
	public RetireDraftResponse retireDraftEarly(RetireDraftRequest request) {
		// TODO: Don't just delete the record, but this is acceptable for now since we don't really care about this history.
		mongo().removeDocument(DRAFTS, json("_id", request.getUserId()));
		return new RetireDraftResponse();
	}
}