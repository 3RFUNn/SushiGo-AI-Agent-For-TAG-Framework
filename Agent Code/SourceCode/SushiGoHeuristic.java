package games.sushigo;

import core.AbstractGameState;
import core.CoreConstants;
import core.components.Deck;
import core.interfaces.IStateHeuristic;
import evaluation.optimisation.TunableParameters;
import games.sushigo.cards.SGCard;
import games.sushigo.cards.SGCard.SGCardType;
import core.components.Counter;


/**
 * This code implements a heuristic evaluation function for the Sushi Go game.
 * The heuristic parameters and evaluation logic were configured and refined through
 * iterative development with assistance from Claude AI (Anthropic, 2024).
 * This approach helped optimize the balance between different scoring factors
 * and strategic elements of the game.
 */



/**
 * A heuristic evaluation function for the Sushi Go game.
 * This class extends TunableParameters and implements IStateHeuristic.
 */
public class SushiGoHeuristic extends TunableParameters implements IStateHeuristic {

    // Base score factors
    double FACTOR_CURRENT_SCORE = 1.0;
    double FACTOR_POTENTIAL_SCORE = 0.8;

    // Set completion factors
    double FACTOR_TEMPURA_SET = 0.7;
    double FACTOR_SASHIMI_SET = 0.7;
    double FACTOR_DUMPLING_SET = 0.6;

    // Special card factors
    double FACTOR_WASABI_VALUE = 0.5;
    double FACTOR_MAKI_POSITION = 0.6;
    double FACTOR_PUDDING_POSITION = 0.5;
    double FACTOR_CHOPSTICKS_VALUE = 0.3;

    /**
     * Constructor for SushiGoHeuristic.
     * Initializes tunable parameters for the heuristic.
     */
    public SushiGoHeuristic() {
        addTunableParameter("FACTOR_CURRENT_SCORE", FACTOR_CURRENT_SCORE);
        addTunableParameter("FACTOR_POTENTIAL_SCORE", FACTOR_POTENTIAL_SCORE);
        addTunableParameter("FACTOR_TEMPURA_SET", FACTOR_TEMPURA_SET);
        addTunableParameter("FACTOR_SASHIMI_SET", FACTOR_SASHIMI_SET);
        addTunableParameter("FACTOR_DUMPLING_SET", FACTOR_DUMPLING_SET);
        addTunableParameter("FACTOR_WASABI_VALUE", FACTOR_WASABI_VALUE);
        addTunableParameter("FACTOR_MAKI_POSITION", FACTOR_MAKI_POSITION);
        addTunableParameter("FACTOR_PUDDING_POSITION", FACTOR_PUDDING_POSITION);
        addTunableParameter("FACTOR_CHOPSTICKS_VALUE", FACTOR_CHOPSTICKS_VALUE);
    }

    /**
     * Resets the heuristic parameters to their default values.
     */
    @Override
    public void _reset() {
        FACTOR_CURRENT_SCORE = (double) getParameterValue("FACTOR_CURRENT_SCORE");
        FACTOR_POTENTIAL_SCORE = (double) getParameterValue("FACTOR_POTENTIAL_SCORE");
        FACTOR_TEMPURA_SET = (double) getParameterValue("FACTOR_TEMPURA_SET");
        FACTOR_SASHIMI_SET = (double) getParameterValue("FACTOR_SASHIMI_SET");
        FACTOR_DUMPLING_SET = (double) getParameterValue("FACTOR_DUMPLING_SET");
        FACTOR_WASABI_VALUE = (double) getParameterValue("FACTOR_WASABI_VALUE");
        FACTOR_MAKI_POSITION = (double) getParameterValue("FACTOR_MAKI_POSITION");
        FACTOR_PUDDING_POSITION = (double) getParameterValue("FACTOR_PUDDING_POSITION");
        FACTOR_CHOPSTICKS_VALUE = (double) getParameterValue("FACTOR_CHOPSTICKS_VALUE");
    }

    /**
     * Evaluates the current game state for a given player.
     * @param gs The current game state.
     * @param playerId The ID of the player for whom the state is being evaluated.
     * @return The heuristic score for the given player.
     */
    @Override
    public double evaluateState(AbstractGameState gs, int playerId) {
        SGGameState state = (SGGameState) gs;
        SGParameters params = (SGParameters) state.getGameParameters();

        // First check win/lose conditions and modify base score
        double score = state.getGameScore(playerId);
        if (gs.getPlayerResults()[playerId] == CoreConstants.GameResult.WIN_GAME)
            return score * 1.5;
        if (gs.getPlayerResults()[playerId] == CoreConstants.GameResult.LOSE_GAME)
            return score * 0.5;

        // Base score from current game state
        score *= FACTOR_CURRENT_SCORE;

        // Evaluate current board position
        Counter[] scores = state.getPlayerScore();
        double maxOppScore = 0;
        for (int i = 0; i < state.getNPlayers(); i++) {
            if (i != playerId) {
                maxOppScore = Math.max(maxOppScore, scores[i].getValue());
            }
        }

        // Add relative position to score
        score += (scores[playerId].getValue() - maxOppScore) * FACTOR_CURRENT_SCORE;

        // Evaluate sets in progress
        evaluateSetsInProgress(state, playerId, params, score);

        // Special card evaluations
        score += evaluateSpecialCards(state, playerId, params);

        return score;
    }

    /**
     * Evaluates the sets in progress for a given player.
     * @param state The current game state.
     * @param playerId The ID of the player.
     * @param params The game parameters.
     * @param score The current score to be modified.
     */
    private void evaluateSetsInProgress(SGGameState state, int playerId, SGParameters params, double score) {
        // Tempura sets
        int tempuraCount = state.getPlayedCardTypes(SGCardType.Tempura, playerId).getValue();
        score += (tempuraCount / 2) * params.valueTempuraPair * FACTOR_TEMPURA_SET;
        if (tempuraCount % 2 == 1) {
            score += params.valueTempuraPair * FACTOR_TEMPURA_SET * 0.3; // Partial set
        }

        // Sashimi sets
        int sashimiCount = state.getPlayedCardTypes(SGCardType.Sashimi, playerId).getValue();
        score += (sashimiCount / 3) * params.valueSashimiTriple * FACTOR_SASHIMI_SET;
        if (sashimiCount % 3 > 0) {
            score += params.valueSashimiTriple * FACTOR_SASHIMI_SET * (sashimiCount % 3) * 0.2; // Partial set
        }

        // Dumpling value
        int dumplingCount = state.getPlayedCardTypes(SGCardType.Dumpling, playerId).getValue();
        if (dumplingCount < params.valueDumpling.length) {
            score += params.valueDumpling[dumplingCount] * FACTOR_DUMPLING_SET;
        }
    }

    /**
     * Evaluates the special cards for a given player.
     * @param state The current game state.
     * @param playerId The ID of the player.
     * @param params The game parameters.
     * @return The score contribution from special cards.
     */
    private double evaluateSpecialCards(SGGameState state, int playerId, SGParameters params) {
        double specialScore = 0.0;

        // Wasabi value
        if (state.getPlayedCardTypes(SGCardType.Wasabi, playerId).getValue() > 0) {
            // Check for unused wasabi
            int wasabiCount = state.getPlayedCardTypes(SGCardType.Wasabi, playerId).getValue();
            int nigiriCount = state.getPlayedCardTypes(SGCardType.SquidNigiri, playerId).getValue() +
                    state.getPlayedCardTypes(SGCardType.SalmonNigiri, playerId).getValue() +
                    state.getPlayedCardTypes(SGCardType.EggNigiri, playerId).getValue();
            if (wasabiCount > nigiriCount) {
                specialScore += params.multiplierWasabi * FACTOR_WASABI_VALUE;
            }
        }

        // Maki competition
        int playerMaki = state.getPlayedCardTypes(SGCardType.Maki, playerId).getValue();
        int maxOpponentMaki = 0;
        for (int i = 0; i < state.getNPlayers(); i++) {
            if (i != playerId) {
                maxOpponentMaki = Math.max(maxOpponentMaki, state.getPlayedCardTypes(SGCardType.Maki, i).getValue());
            }
        }
        if (playerMaki >= maxOpponentMaki) {
            specialScore += params.valueMakiMost * FACTOR_MAKI_POSITION;
        } else if (playerMaki == maxOpponentMaki - 1) {
            specialScore += params.valueMakiSecond * FACTOR_MAKI_POSITION;
        }

        // Pudding position
        if (state.getRoundCounter() == params.nRounds - 1) {
            int playerPudding = state.getPlayedCardTypesAllGame()[playerId].get(SGCardType.Pudding).getValue();
            int maxPudding = 0, minPudding = Integer.MAX_VALUE;
            for (int i = 0; i < state.getNPlayers(); i++) {
                if (i != playerId) {
                    int oppPudding = state.getPlayedCardTypesAllGame()[i].get(SGCardType.Pudding).getValue();
                    maxPudding = Math.max(maxPudding, oppPudding);
                    minPudding = Math.min(minPudding, oppPudding);
                }
            }
            if (playerPudding > maxPudding) {
                specialScore += params.valuePuddingMost * FACTOR_PUDDING_POSITION;
            } else if (playerPudding < minPudding) {
                specialScore += params.valuePuddingLeast * FACTOR_PUDDING_POSITION;
            }
        }

        // Chopsticks value
        if (state.getPlayedCardTypes(SGCardType.Chopsticks, playerId).getValue() > 0) {
            specialScore += 5 * FACTOR_CHOPSTICKS_VALUE; // Value of potential double play
        }

        return specialScore;
    }

    /**
     * Creates a copy of the current SushiGoHeuristic instance.
     * @return A new instance of SushiGoHeuristic with the same parameter values.
     */
    @Override
    protected SushiGoHeuristic _copy() {
        SushiGoHeuristic copy = new SushiGoHeuristic();
        copy.FACTOR_CURRENT_SCORE = FACTOR_CURRENT_SCORE;
        copy.FACTOR_POTENTIAL_SCORE = FACTOR_POTENTIAL_SCORE;
        copy.FACTOR_TEMPURA_SET = FACTOR_TEMPURA_SET;
        copy.FACTOR_SASHIMI_SET = FACTOR_SASHIMI_SET;
        copy.FACTOR_DUMPLING_SET = FACTOR_DUMPLING_SET;
        copy.FACTOR_WASABI_VALUE = FACTOR_WASABI_VALUE;
        copy.FACTOR_MAKI_POSITION = FACTOR_MAKI_POSITION;
        copy.FACTOR_PUDDING_POSITION = FACTOR_PUDDING_POSITION;
        copy.FACTOR_CHOPSTICKS_VALUE = FACTOR_CHOPSTICKS_VALUE;
        return copy;
    }

    /**
     * Checks if the current instance is equal to another SushiGoHeuristic instance.
     * @param o The object to compare with.
     * @return True if the objects are equal, false otherwise.
     */
    @Override
    protected boolean _equals(Object o) {
        if (o instanceof SushiGoHeuristic) {
            SushiGoHeuristic other = (SushiGoHeuristic) o;
            return other.FACTOR_CURRENT_SCORE == FACTOR_CURRENT_SCORE &&
                    other.FACTOR_POTENTIAL_SCORE == FACTOR_POTENTIAL_SCORE &&
                    other.FACTOR_TEMPURA_SET == FACTOR_TEMPURA_SET &&
                    other.FACTOR_SASHIMI_SET == FACTOR_SASHIMI_SET &&
                    other.FACTOR_DUMPLING_SET == FACTOR_DUMPLING_SET &&
                    other.FACTOR_WASABI_VALUE == FACTOR_WASABI_VALUE &&
                    other.FACTOR_MAKI_POSITION == FACTOR_MAKI_POSITION &&
                    other.FACTOR_PUDDING_POSITION == FACTOR_PUDDING_POSITION &&
                    other.FACTOR_CHOPSTICKS_VALUE == FACTOR_CHOPSTICKS_VALUE;
        }
        return false;
    }

    /**
     * Instantiates a new SushiGoHeuristic instance.
     * @return A new instance of SushiGoHeuristic.
     */
    @Override
    public SushiGoHeuristic instantiate() {
        return this._copy();
    }
}