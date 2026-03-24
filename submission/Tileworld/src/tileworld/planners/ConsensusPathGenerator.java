package tileworld.planners;

import java.util.ArrayList;
import java.util.Collections;
import tileworld.agent.ConsensusMemory;
import tileworld.agent.TWAgent;
import tileworld.environment.TWEnvironment;

/**
 * A specialized A* Pathfinder for Implicit Coordination.
 * * CRITICAL LOGIC:
 * This planner ignores the agent's private sensor data (TWAgentWorkingMemory).
 * Instead, it ONLY checks the ConsensusMemory (Layer 2) for obstacles.
 * * This ensures that when Agent A and Agent B calculate the distance to a tile,
 * they use the exact same map snapshot, even if Agent A is standing right next
 * to a dynamic obstacle that Agent B hasn't seen yet.
 */
public class ConsensusPathGenerator implements TWPathGenerator {

    private ArrayList<Node> closed = new ArrayList<>();
    private SortedList open = new SortedList();
    private TWEnvironment map;
    private int maxSearchDistance;
    private Node[][] nodes;
    private TWAgent agent;

    public ConsensusPathGenerator(TWEnvironment map, TWAgent agent, int maxSearchDistance) {
        this.agent = agent;
        this.map = map;
        this.maxSearchDistance = maxSearchDistance;

        nodes = new Node[map.getxDimension()][map.getyDimension()];
        for (int x = 0; x < map.getxDimension(); x++) {
            for (int y = 0; y < map.getyDimension(); y++) {
                nodes[x][y] = new Node(x, y);
            }
        }
    }

    public TWPath findPath(int sx, int sy, int tx, int ty) {
        // --- CRITICAL CHANGE START ---
        // Access ConsensusMemory directly to check the Shared Map
        ConsensusMemory memory = (ConsensusMemory) agent.getMemory();

        // If the target itself is blocked in the consensus map, we can't go there
        if (memory.isConsensusBlocked(tx, ty)) {
            return null;
        }
        // --- CRITICAL CHANGE END ---

        nodes[sx][sy].cost = 0;
        nodes[sx][sy].depth = 0;
        closed.clear();
        open.clear();
        open.add(nodes[sx][sy]);

        nodes[tx][ty].parent = null;

        int maxDepth = 0;
        while ((maxDepth < maxSearchDistance) && (open.size() != 0)) {
            Node current = getFirstInOpen();
            if (current == nodes[tx][ty]) {
                break;
            }

            removeFromOpen(current);
            addToClosed(current);

            // Check neighbors (standard 4-direction movement)
            for (int x = -1; x < 2; x++) {
                for (int y = -1; y < 2; y++) {
                    if ((x == 0) && (y == 0)) continue;
                    if ((x != 0) && (y != 0)) continue; // No diagonal

                    int xp = x + current.x;
                    int yp = y + current.y;

                    // --- CRITICAL CHANGE START ---
                    // Standard check: is it in bounds?
                    // Custom check: !memory.isConsensusBlocked (Ignores private sensors)
                    if (isValidLocation(sx, sy, xp, yp) && !memory.isConsensusBlocked(xp, yp)) {
                    // --- CRITICAL CHANGE END ---

                        double nextStepCost = current.cost + map.getDistance(current.x, current.y, xp, yp);
                        Node neighbour = nodes[xp][yp];
                        neighbour.visited = true;

                        if (nextStepCost < neighbour.cost) {
                            if (inOpenList(neighbour)) removeFromOpen(neighbour);
                            if (inClosedList(neighbour)) removeFromClosed(neighbour);
                        }

                        if (!inOpenList(neighbour) && !(inClosedList(neighbour))) {
                            neighbour.cost = nextStepCost;
                            neighbour.heuristic = getCost(xp, yp, tx, ty);
                            maxDepth = Math.max(maxDepth, neighbour.setParent(current));
                            addToOpen(neighbour);
                        }
                    }
                }
            }
        }

        if (nodes[tx][ty].parent == null) return null;

        TWPath path = new TWPath(tx, ty);
        Node target = nodes[tx][ty];
        target = target.parent;
        while (target != nodes[sx][sy]) {
            path.prependStep(target.x, target.y);
            target = target.parent;
        }
        path.prependStep(sx, sy);
        return path;
    }

    // --- Standard Helpers (Copied to keep class self-contained) ---
    private Node getFirstInOpen() { return (Node) open.first(); }
    private void addToOpen(Node node) { open.add(node); }
    private boolean inOpenList(Node node) { return open.contains(node); }
    private void removeFromOpen(Node node) { open.remove(node); }
    private void addToClosed(Node node) { closed.add(node); }
    private boolean inClosedList(Node node) { return closed.contains(node); }
    private void removeFromClosed(Node node) { closed.remove(node); }

    private boolean isValidLocation(int sx, int sy, int x, int y) {
        return (map.isValidLocation(x, y) && ((sx != x) || (sy != y)));
    }

    public double getCost(int currentX, int currentY, int goalX, int goalY) {
        return Math.sqrt(Math.pow(goalX - currentX, 2) + Math.pow(goalY - currentY, 2));
    }

    // --- Inner Classes ---
    private class SortedList {
        private ArrayList<Node> list = new ArrayList<>();
        public Object first() { return list.get(0); }
        public void clear() { list.clear(); }
        public void add(Node o) { list.add(o); Collections.sort(list); }
        public void remove(Node o) { list.remove(o); }
        public int size() { return list.size(); }
        public boolean contains(Node o) { return list.contains(o); }
    }

    private class Node implements Comparable<Node> {
        private int x, y;
        private double cost;
        private Node parent;
        private double heuristic;
        private int depth;
        private boolean visited;

        public Node(int x, int y) { this.x = x; this.y = y; }
        public int setParent(Node parent) { depth = parent.depth + 1; this.parent = parent; return depth; }
        public int compareTo(Node other) {
            return Double.compare(heuristic + cost, other.heuristic + other.cost);
        }
    }
}