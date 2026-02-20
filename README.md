# multi-agent-tileworld

## Overview
This repository hosts collaborative work for a group project for a multi-agent systems course. The primary objective is to design, implement, and optimize a team of 6 autonomous agents that collaborate within the highly dynamic Tileworld environment.

The agents must efficiently navigate a grid, manage limited fuel resources, and coordinate their actions to discover tiles and carry them to holes before the objects naturally expire. The ultimate goal of the architecture is to maximize the team's overall reward through strategic pathfinding, efficient task allocation, and robust communication protocols.

## Repository Structure

To maintain a clean workflow and separate the experimental sandbox from the graded deliverable, the repository is organized into two main directories:

### 1. [`prototypes/`](prototypes/)
This directory is designated for experimental implementations, proof-of-concept architectures, and isolated testing.
* **Purpose:** A safe space to build and test new ideas (such as implicit coordination, new A* heuristics, or dynamic map zoning) without the risk of breaking the main codebase.
* **Current Contents:** You can find initial prototype implementations here to review the baseline mechanics and shared memory structures we have developed so far.

### 2. [`submission/`](submission/)
This directory contains the finalized, polished, and fully integrated codebase that will be packaged for final project evaluation.
* **Purpose:** The "production" environment. Code should only be moved or merged into this folder after it has been thoroughly tested and agreed upon by the team to ensure the final deliverable remains stable and bug-free.