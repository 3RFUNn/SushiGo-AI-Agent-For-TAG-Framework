# Running Experiments with TAG Framework

This guide provides instructions to set up and run experiments in the Tabletop Games (TAG) framework using custom configurations.

## Steps to Configure and Run Experiments

1. **Copy Experiment Configuration**:
   - Copy `rungames3.json` and paste it into the `json/experiments` directory.

2. **Copy Test Players Folder**:
   - Copy the entire `testPlayers` folder and paste it inside the `json` directory.

3. **Copy SushiGoHeuristic File**:
   - Copy the `SushiGoHeuristic` file and paste it into the `games/sushigo` directory.

4. **Copy ProgressiveBias_Lab and alphaAMAF Files**:
   - Copy the `ProgressiveBias_Lab` and `alphaAMAF` files and paste them into `src/main/java/players`.

5. **Configure RunGames File**:
   - Go to `evaluation/RunGames` and edit the file configurations to set the correct JSON path.

6. **Add Configuration Path**:
   - Add the following path to the configuration: `"config=json/experiments/rungames3.json"`.

7. **Run the Code**:
   - Execute the `RunGames` file. This will start the experiment based on the specified configurations.

8. **Review Output**:
   - After running, the results will be generated in `outputdir/TournamentResults.txt`. This file contains the tournament results and performance metrics of the agents.

## Output

The results in `outputdir/TournamentResults.txt` provide insights into agent performance, rankings, and win rates for the configured experiment.

Following these steps will allow you to set up and run the experiment using custom agent configurations within the TAG framework.

## Acknowledgments

This project has leveraged generative AI tools such as OpenAI’s ChatGPT and Anthropic's Claude AI to gain insights and assist in generating some parts of the code. These AI tools provided support for developing configurations, optimizing processes, and improving the overall framework setup.
