package tileworld.agent;

import tileworld.environment.TWEnvironment;

/**
 * TWAgent5
 */
public class TWAgent5 extends TWBaseAgent {

    // --- CONSTANTS ---

    // --- CONSTRUCTORS ---

    /**
     * Constructs a new TWAgent5.
     *
     * @param name      The unique identifier for this agent.
     * @param xpos      The starting X coordinate.
     * @param ypos      The starting Y coordinate.
     * @param env       The Tileworld environment.
     * @param fuelLevel The starting fuel level.
     */
    public TWAgent5(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, xpos, ypos, env, fuelLevel);
    }

    // --- CORE BEHAVIOR ---

    /**
     * Implements the specific worker logic for this agent type.
     * This is only called if the following higher-priority tasks are satisfied:
     * 1. Consensus Memory Sync (Broadcasted Messages)
     * 2. Locating Fuel Station
     * 3. Fuel Level Safety Check
     * 4. Object Lifetime Monitoring (First Responder)
     * 5. Automatically-Handled High-Priority Tasks (Reflex)
     * * @return TWThought The decided action and direction for this step.
     */
    @Override
    protected TWThought executeWorkerLogic() {
        return executeExploreMove(); // placeholder
    }
}
