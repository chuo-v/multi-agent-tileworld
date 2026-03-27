package tileworld.agent;

import java.util.List;
import sim.util.Int2D;
import tileworld.environment.TWEnvironment;

/**
 * TWAgent3 - "The Generalist"
 * Strategy: Evaluates all valid targets and chooses option with highest standardized score.
 */
public class TWAgent3 extends TWBaseAgent {

    public TWAgent3(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, xpos, ypos, env, fuelLevel);
    }

    @Override
    protected TWThought executeWorkerLogic() {
        Int2D bestTarget = null;
        int bestScore = Integer.MIN_VALUE;
        TWAction bestAction = null;

        // Evaluate Tile Candidates (Lower Score / Longer Distance)
        if (this.carriedTiles.size() < 3) { // only if we have space
            List<Int2D> tileCandidates = getLowerPriorityTilesFromConsensusMemory();
            for (Int2D target : tileCandidates) {
                int score = standardizedScoreForTile(target.x, target.y);
                if (score > bestScore) {
                    if (isBestCandidate(target.x, target.y, score, this.getEnvironment().getMessages(), TWAction.PICKUP)) {
                        bestScore = score;
                        bestTarget = target;
                        bestAction = TWAction.PICKUP;
                    }
                }
            }
        }

        // Evaluate Hole Candidates (Lower Score / Longer Distance)
        if (this.carriedTiles.size() > 0) { // only if we have tiles
            List<Int2D> holeCandidates = getLowerPriorityHolesFromConsensusMemory();
            for (Int2D target : holeCandidates) {
                int score = standardizedScoreForHole(target.x, target.y);
                if (score > bestScore) {
                    if (isBestCandidate(target.x, target.y, score, this.getEnvironment().getMessages(), TWAction.PUTDOWN)) {
                        bestScore = score;
                        bestTarget = target;
                        bestAction = TWAction.PUTDOWN;
                    }
                }
            }
        }

        if (bestTarget != null) {
            this.explorationTarget = null; // important to reset exploration target if best target found
            return executePathTo(bestTarget.x, bestTarget.y, bestAction);
        } else {
            return executeExploreMove();
        }
    }
}
