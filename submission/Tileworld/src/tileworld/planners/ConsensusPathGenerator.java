package tileworld.planners;

import java.util.PriorityQueue;
import tileworld.agent.ConsensusMemory;
import tileworld.agent.TWAgent;
import tileworld.environment.TWEnvironment;

/**
 * A specialized A* Pathfinder for Implicit Coordination.
 * * This planner ignores the agent's private sensor data (TWAgentWorkingMemory).
 * Instead, it ONLY checks the ConsensusMemory (Layer 2) for obstacles.
 * * This ensures that when Agent A and Agent B calculate the distance to a tile,
 * they use the exact same map snapshot, even if Agent A is standing right next
 * to a dynamic obstacle that Agent B hasn't seen yet.
 */
public class ConsensusPathGenerator implements TWPathGenerator {

    // Counter used to instantly verify if a node is in the open/closed list for the current path search
    private long currentSearchId = 0;
    private PriorityQueue<SearchEntry> open = new PriorityQueue<>();
    private final int xDim, yDim; // Dimensions cached for speed
    private int maxSearchDistance;
    private Node[][] nodes;
    private TWAgent agent;

    public ConsensusPathGenerator(TWEnvironment map, TWAgent agent, int maxSearchDistance) {
        this.agent = agent;
        this.xDim = map.getxDimension();
        this.yDim = map.getyDimension();
        this.maxSearchDistance = maxSearchDistance;

        nodes = new Node[xDim][yDim];
        for (int x = 0; x < xDim; x++) {
            for (int y = 0; y < yDim; y++) {
                nodes[x][y] = new Node(x, y);
            }
        }
    }

    public TWPath findPath(int sx, int sy, int tx, int ty) {
        // If the minimum possible steps (Manhattan) exceeds limit, return null
        if (Math.abs(sx - tx) + Math.abs(sy - ty) > maxSearchDistance) return null;

        currentSearchId++;
        ConsensusMemory memory = (ConsensusMemory) agent.getMemory();
        // If the target itself is blocked in the consensus map, we can't go there
        if (memory.isConsensusBlocked(tx, ty)) {
            return null;
        }

        Node startNode = nodes[sx][sy];
        startNode.cost = 0;
        startNode.depth = 0;
        startNode.heuristic = getCost(sx, sy, tx, ty);
        startNode.openSearchId = currentSearchId;

        open.clear();
        open.add(new SearchEntry(startNode, startNode.heuristic));

        nodes[tx][ty].parent = null;

        while (!open.isEmpty()) {
            SearchEntry entry = open.poll();
            Node current = entry.node;

            // Lazy Deletion: Check if this entry is stale
            if (entry.fScore > (current.cost + current.heuristic)) continue;
            if (inClosedList(current)) continue;

            if (current == nodes[tx][ty]) break;

            // Limit search depth per-path
            if (current.depth >= maxSearchDistance) continue;

            addToClosed(current);

            // Check neighbors (standard 4-direction movement)
            for (int x = -1; x < 2; x++) {
                for (int y = -1; y < 2; y++) {
                    if ((x == 0 && y == 0) || (x != 0 && y != 0)) continue;

                    int xp = x + current.x;
                    int yp = y + current.y;

                    // Standard check: is it in bounds?
                    // Custom check: !memory.isConsensusBlocked (Ignores private sensors)
                    if (xp >= 0 && xp < xDim && yp >= 0 && yp < yDim && !memory.isConsensusBlocked(xp, yp)) {
                        double nextStepCost = current.cost + 1.0;
                        Node neighbour = nodes[xp][yp];

                        // Only update if the node is brand new or we found a shorter path
                        boolean isNew = !inOpenList(neighbour) && !inClosedList(neighbour);
                        if (isNew || nextStepCost < neighbour.cost) {
                            neighbour.cost = nextStepCost;
                            if (isNew) neighbour.heuristic = getCost(xp, yp, tx, ty);

                            neighbour.setParent(current);
                            neighbour.openSearchId = currentSearchId; // Mark as in-queue

                            // Add new entry with current best f-score
                            open.add(new SearchEntry(neighbour, neighbour.cost + neighbour.heuristic));
                        }
                    }
                }
            }
        }

        if (nodes[tx][ty].parent == null) return null;

        TWPath path = new TWPath(tx, ty);
        Node target = nodes[tx][ty];
        while (target != nodes[sx][sy]) {
            path.prependStep(target.x, target.y);
            target = target.parent;
        }
        path.prependStep(sx, sy);
        return path;
    }

    private boolean inOpenList(Node node) { return node.openSearchId == currentSearchId; }
    private void addToClosed(Node node) { node.closedSearchId = currentSearchId; }
    private boolean inClosedList(Node node) { return node.closedSearchId == currentSearchId; }

    public double getCost(int currentX, int currentY, int goalX, int goalY) {
        // Use multiplication instead of Math.pow for faster calculations
        int dx = goalX - currentX;
        int dy = goalY - currentY;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Wrapper to keep the PriorityQueue heap structure safe */
    private class SearchEntry implements Comparable<SearchEntry> {
        Node node;
        double fScore;
        SearchEntry(Node n, double f) { this.node = n; this.fScore = f; }
        @Override
        public int compareTo(SearchEntry o) { return Double.compare(this.fScore, o.fScore); }
    }

    private class Node {
        int x, y;
        double cost, heuristic;
        Node parent;
        int depth;
        long openSearchId = -1, closedSearchId = -1;

        Node(int x, int y) { this.x = x; this.y = y; }
        void setParent(Node parent) {
            this.depth = parent.depth + 1;
            this.parent = parent;
        }
    }
}