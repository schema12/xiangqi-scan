package org.petero.droidfish.player;

/** Receives one {@link SearchOutcome} per completed engine search (bestmove). */
public interface SearchOutcomeListener {
    void onSearchOutcome(SearchOutcome outcome);
}
