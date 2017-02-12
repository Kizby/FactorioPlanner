package com.neurosynthase.factorio;

import static com.naef.jnlua.LuaType.TABLE;

import com.naef.jnlua.LuaRuntimeException;
import com.naef.jnlua.LuaState;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Main {
    public static Map<String, Resource> resources = new TreeMap<>();
    public static Map<String, Crafter> crafters = new TreeMap<>();
    public static Map<String, Integer> moduleSlots = new TreeMap<>();
    public static Map<String, Recipe> recipes = new TreeMap<>();
    public static Map<String, Module> modules = new TreeMap<>();
    public static Map<String, String> subgroups = new TreeMap<>();
    public static Map<String, Set<Recipe>> resultRecipeMap = new TreeMap<>();
    public static Set<String> unlockedRecipes = new TreeSet<>();
    public static Map<String, Technology> technologies = new TreeMap<>();
    public static Map<String, Technology> recipeTechnologyMap = new TreeMap<>();
    private static Set<String> intermediateGoals = new TreeSet<>();

    public static void main(String[] args) {
        Map<Object, Object> data = GetData();
        parseData(data);

        Map<String, Number> inventory = new TreeMap<>();
        inventory.put("player", 1); // why not?
        inventory.put("burner-mining-drill", 1);
        inventory.put("stone-furnace", 1);
        inventory.put("pistol", 1);
        inventory.put("firearm-magazine", 10);
        //inventory.put("iron-plate", 8);

        Set<Technology> unlockedTechnologies = new TreeSet<>();

        Map<String, Number> goal = new TreeMap<>();
        goal.put("rocket-part", 100);
        goal.put("satellite", 1);

        List<Step> steps = new ArrayList<>();
        if (solve(inventory, unlockedTechnologies, goal, steps)) {
            collapse(steps);
            System.out.println(String.join("\n", steps.stream().map(Step::toString).collect(Collectors.toList())));
        } else {
            System.out.println("Impossible!");
        }
    }

    private static void collapse(List<Step> steps) {
        Map<Class<? extends Step>, Set<String>> done = new HashMap<>();
        done.put(MineStep.class, new TreeSet<>());
        done.put(CraftStep.class, new TreeSet<>());
        done.put(ResearchStep.class, new TreeSet<>());
        boolean changed = true;
        while (changed) {
            changed = false;
            ListIterator<Step> iterator = steps.listIterator();
            while (iterator.hasNext()) {
                Step base = iterator.next();
                if (done.get(base.getClass()).contains(base.name)) {
                    continue;
                }
                done.get(base.getClass()).add(base.name);
                changed = true;
                while (iterator.hasNext()) {
                    Step next = iterator.next();
                    if (next.getClass() != base.getClass() || !next.name.equals(base.name)) {
                        continue;
                    }
                    // Found something to glom on!
                    if (base instanceof MineStep) {
                        ((MineStep)base).resourceCount += ((MineStep)next).resourceCount;
                    } else if (base instanceof CraftStep) {
                        ((CraftStep)base).craftCount += ((CraftStep)next).craftCount;
                    } else if (base instanceof ResearchStep) {
                        // wtf!? Researched the same tech multiple times?
                        continue;
                    } else {
                        System.err.println("Need to add collapse logic for new Step kind " + base.getClass().getCanonicalName());
                        continue;
                    }
                    iterator.remove();
                }
                break;
            }
        }
    }

    private static<K> void add(Map<K, Number> map, K key, Number addend) {
        Number sum;
        if (!map.containsKey(key)) {
            sum = 0.0;
        } else {
            sum = map.get(key);
        }
        sum = sum.doubleValue() + addend.doubleValue();
        if (0.0 == sum.doubleValue()) {
            map.remove(key);
        } else {
            map.put(key, sum);
        }
    }

    private static<K> void subtract(Map<K, Number> map, K key, Number subtrahend) {
        Number difference;
        if (!map.containsKey(key)) {
            difference = 0.0;
        } else {
            difference = map.get(key);
        }
        difference = difference.doubleValue() - subtrahend.doubleValue();
        if (0.0 == difference.doubleValue()) {
            map.remove(key);
        } else {
            map.put(key, difference);
        }
    }

    private static boolean solve(Map<String, Number> inventory, Set<Technology> unlockedTechnologies, Map<String, Number> goal, List<Step> stepsSoFar) {
        for (Map.Entry<String, Number> entry : goal.entrySet()) {
            String item = entry.getKey();
            double need = entry.getValue().doubleValue();
            double have = 0.0;
            if (inventory.containsKey(item)) {
                have = inventory.get(item).doubleValue();
            }
            if (have >= need) {
                // We're good!
                continue;
            }
            need -= have;
            if (resources.containsKey(item)) {
                Resource resource = resources.get(item);
                String minerToUse = null;
                for (String miner : resource.miningTimes.keySet()) {
                    if (inventory.containsKey(miner) && inventory.get(miner).intValue() > 0) {
                        minerToUse = miner;
                        break;
                    }
                }
                if (null == minerToUse) {
                    // Need to make something to obtain it!
                    for (String miner : resource.miningTimes.keySet()) {
                        if (intermediateGoals.contains(miner)) {
                            // Already working on this, so it won't help!
                            continue;
                        }
                        intermediateGoals.add(miner);

                        Map<String, Number> newGoal = new TreeMap<>();
                        newGoal.put(miner, 1);
                        if (solve(inventory, unlockedTechnologies, newGoal, stepsSoFar)) {
                            // Got it!
                            minerToUse = miner;
                        }

                        intermediateGoals.remove(miner);
                        if (null != minerToUse) {
                            break;
                        }
                    }
                    if (null == minerToUse) {
                        // Uh oh! Guess this is impossible...
                        return false;
                    }
                }

                stepsSoFar.add(new MineStep(resources.get(item), need, minerToUse, new ArrayList<>()));
                add(inventory, item, need);

                return solve(inventory, unlockedTechnologies, goal, stepsSoFar);
            }
            if (resultRecipeMap.containsKey(item)) {
                Set<Recipe> candidateRecipes = resultRecipeMap.get(item);
                // First pass to see if any are immediately viable, second to craft as necessary
                for (int pass = 1; pass <= 2; pass++) {
                    boolean craftAsNecessary = (2 == pass);
                    for (Recipe recipe : candidateRecipes) {
                        if (!unlockedRecipes.contains(recipe.name) && !unlockedTechnologies.contains(recipeTechnologyMap.get(recipe.name))) {
                            // Not unlocked
                            continue;
                        }

                        String crafterToUse = null;
                        for (Crafter crafter : crafters.values()) {
                            if (!inventory.containsKey(crafter.name)) {
                                continue;
                            }
                            if (crafter.categories.contains(recipe.category)) {
                                // We can craft it!
                                crafterToUse = crafter.name;
                                break;
                            }
                        }
                        if (null == crafterToUse) {
                            if (!craftAsNecessary) {
                                continue;
                            }
                            // Need to make something to obtain it!
                            for (Crafter crafter : crafters.values()) {
                                if (!crafter.categories.contains(recipe.category)) {
                                    // Not relevant
                                    continue;
                                }
                                if (intermediateGoals.contains(crafter.name)) {
                                    // Already working on this, so it won't help!
                                    continue;
                                }
                                intermediateGoals.add(crafter.name);

                                Map<String, Number> newGoal = new TreeMap<>();
                                newGoal.put(crafter.name, 1);
                                if (solve(inventory, unlockedTechnologies, newGoal, stepsSoFar)) {
                                    // Got it!
                                    crafterToUse = crafter.name;
                                }

                                intermediateGoals.remove(crafter.name);
                                if (null != crafterToUse) {
                                    break;
                                }
                            }
                            if (null == crafterToUse) {
                                // Uh oh! Guess this is impossible...
                                return false;
                            }
                        }
                        int count = (int) Math.ceil(need / recipe.results.get(item).doubleValue());

                        Map<String, Number> newGoal = new TreeMap<>();
                        boolean haveIngredients = true;
                        for (Map.Entry<String, Number> ingredientEntry : recipe.ingredients.entrySet()) {
                            Number desired = ingredientEntry.getValue().doubleValue() * count;
                            if (craftAsNecessary) {
                                newGoal.put(ingredientEntry.getKey(), desired);
                            } else {
                                if (!inventory.containsKey(ingredientEntry.getKey()) ||
                                        inventory.get(ingredientEntry.getKey()).doubleValue() < desired.doubleValue()) {
                                    haveIngredients = false;
                                    break;
                                }
                            }
                        }
                        if (!haveIngredients || !solve(inventory, unlockedTechnologies, newGoal, stepsSoFar)) {
                            // This recipe won't help us... (might have wasted making a crafter, oh well)
                            continue;
                        }

                        stepsSoFar.add(new CraftStep(recipe, count, crafterToUse, new ArrayList<>()));
                        for (Map.Entry<String, Number> madeEntry : recipe.ingredients.entrySet()) {
                            subtract(inventory, madeEntry.getKey(), madeEntry.getValue().doubleValue() * count);
                        }
                        for (Map.Entry<String, Number> resultEntry : recipe.results.entrySet()) {
                            add(inventory, resultEntry.getKey(), resultEntry.getValue().doubleValue() * count);
                        }
                        return solve(inventory, unlockedTechnologies, goal, stepsSoFar);
                    }
                }
                // Might need a new tech!
                Set<Technology> candidateTechnologies = new TreeSet<>();
                for (Recipe recipe : candidateRecipes) {
                    Technology candidateTech = recipeTechnologyMap.get(recipe.name);
                    if (!unlockedTechnologies.contains(candidateTech)) {
                        candidateTechnologies.add(recipeTechnologyMap.get(recipe.name));
                    }
                }
                Technology nextTechnology = null;
                for (Technology technology : candidateTechnologies) {
                    boolean isViable = true;
                    for (String prerequisite : technology.prerequisites) {
                        // This check is probably good enough for now...
                        if (candidateTechnologies.contains(technologies.get(prerequisite))) {
                            isViable = false;
                        }
                    }
                    if (!isViable) {
                        continue;
                    }
                    nextTechnology = technology;
                    break;
                }

                if (null == nextTechnology || !solve(inventory, unlockedTechnologies, nextTechnology, stepsSoFar)) {
                    return false;
                }
                return solve(inventory, unlockedTechnologies, goal, stepsSoFar);
            }
            return false; // Can't mine or craft it!
        }
        // Have everything we need!
        return true;
    }

    private static Set<Technology> intermediateTechs = new TreeSet<>();
    private static boolean solve(Map<String, Number> inventory, Set<Technology> unlockedTechnologies, Technology technology, List<Step> stepsSoFar) {
        if (unlockedTechnologies.contains(technology)) {
            // We're good!
            return true;
        }
        for (String prerequisite : technology.prerequisites) {
            Technology prereqTech = technologies.get(prerequisite);
            if (unlockedTechnologies.contains(prereqTech)) {
                continue;
            }
            if (intermediateTechs.contains(prereqTech)) {
                // wtf!?
                return false;
            }
            intermediateTechs.add(technologies.get(prerequisite));

            if (!solve(inventory, unlockedTechnologies, technologies.get(prerequisite), stepsSoFar)) {
                intermediateTechs.remove(prereqTech);
                return false;
            }

            intermediateTechs.remove(prereqTech);
        }

        // First make sure we have at least one lab and that we can power it
        if (!inventory.containsKey("lab") || inventory.get("lab").doubleValue() < 1.0) {
            if (intermediateGoals.contains("lab")) {
                // wtf, recursive!?
                return false;
            }
            intermediateGoals.add("lab");

            Map<String, Number> newGoal = new TreeMap<>();
            newGoal.put("lab", 1);
            newGoal.put("offshore-pump", 1);
            newGoal.put("boiler", 1);
            newGoal.put("steam-engine", 1);
            newGoal.put("small-electric-pole", 1);
            if (!solve(inventory, unlockedTechnologies, newGoal, stepsSoFar)) {
                // Can't make a lab? We're doomed
                intermediateGoals.remove("lab");
                return false;
            }

            intermediateGoals.remove("lab");
        }

        Map<String, Number> goal = new TreeMap<>();
        for (Map.Entry<String, Number> entry : technology.unitIngredients.entrySet()) {
            String item = entry.getKey();
            Double count = entry.getValue().doubleValue() * technology.unitCount;
            goal.put(item, count);
        }
        if (!solve(inventory, unlockedTechnologies, goal, stepsSoFar)) {
            return false;
        }

        for (Map.Entry<String, Number> entry : goal.entrySet()) {
            subtract(inventory, entry.getKey(), entry.getValue());
        }
        unlockedTechnologies.add(technology);
        stepsSoFar.add(new ResearchStep(technology, new ArrayList<>()));
        return true;
    }

    @SuppressWarnings("unchecked")
    private static void parseData(Map<Object, Object> data) {
        Map<String, Object> playerMap = (Map<String, Object>) data.get("player");
        playerMap = (Map<String, Object>) playerMap.get("player");

        Map<String, Map<String, Object>> resourceMap = (Map<String, Map<String, Object>>) data.get("resource");
        Map<String, Map<String, Object>> miningDrillMap = (Map<String, Map<String, Object>>) data.get("mining-drill");
        ParseResources(playerMap, resourceMap, miningDrillMap);

        Map<String, Map<String, Object>> assemblingMachineMap = (Map<String, Map<String, Object>>) data.get("assembling-machine");
        Map<String, Map<String, Object>> furnaceMap = (Map<String, Map<String, Object>>) data.get("furnace");
        Map<String, Map<String, Object>> rocketSiloMap = (Map<String, Map<String, Object>>) data.get("rocket-silo");
        ParseCrafters(playerMap, assemblingMachineMap, furnaceMap, rocketSiloMap);

        Map<String, Map<String, Object>> labMap = (Map<String, Map<String, Object>>) data.get("lab");
        ParseLabs(labMap);

        Map<String, Map<String, Object>> recipeMap = (Map<String, Map<String, Object>>) data.get("recipe");
        ParseRecipes(recipeMap);

        Map<String, Map<String, Object>> technologyMap = (Map<String, Map<String, Object>>) data.get("technology");
        ParseTechnologies(technologyMap);

        Map<String, Map<String, Object>> itemMap = (Map<String, Map<String, Object>>) data.get("item");
        ParseItems(itemMap);

        Map<String, Map<String, Object>> moduleMap = (Map<String, Map<String, Object>>) data.get("module");
        ParseModules(moduleMap);
    }

    @SuppressWarnings("unchecked")
    private static void ParseLabs(Map<String, Map<String, Object>> labMap) {
        for (Map.Entry<String, Map<String, Object>> entry : labMap.entrySet()) {
            String name = entry.getKey();

            Object moduleSpec = entry.getValue().get("module_specification");
            if (moduleSpec != null && moduleSpec instanceof Map) {
                int slotCount = ((Map<String, Integer>)moduleSpec).get("module_slots");
                moduleSlots.put(name, slotCount);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void ParseModules(Map<String, Map<String, Object>> moduleMap) {
        for (Map.Entry<String, Map<String, Object>> entry : moduleMap.entrySet()) {
            String name = entry.getKey();
            String subgroup = (String) entry.getValue().get("subgroup");
            subgroups.put(name, subgroup); // Should always be "module", but may as well be explicit

            Map<String, Map<String, Number>> effectMap = (Map<String, Map<String, Number>>) entry.getValue().get("effect");
            Map<String, Double> effects = new TreeMap<>();
            for (Map.Entry<String, Map<String, Number>> effectsEntry : effectMap.entrySet()) {
                effects.put(effectsEntry.getKey(), effectsEntry.getValue().get("bonus").doubleValue());
            }

            List<String> limitationList = (List<String>) entry.getValue().get("limitation");
            Set<String> limitations = null;
            if (null != limitationList) {
                limitations = new TreeSet<>(limitationList);
            }
            modules.put(name, new Module(name, effects, limitations));
        }
    }


    @SuppressWarnings("unchecked")
    private static void ParseItems(Map<String, Map<String, Object>> itemMap) {
        for (Map.Entry<String, Map<String, Object>> entry : itemMap.entrySet()) {
            String name = entry.getKey();
            String subgroup = (String) entry.getValue().get("subgroup");
            subgroups.put(name, subgroup);
        }
    }

    @SuppressWarnings("unchecked")
    private static void ParseTechnologies(Map<String, Map<String, Object>> technologyMap) {
        for (Map.Entry<String, Map<String, Object>> entry : technologyMap.entrySet()) {
            String name = entry.getKey();
            List<String> prerequisites = (List<String>) entry.getValue().get("prerequisites");
            if (null == prerequisites) {
                prerequisites = new ArrayList<>();
            }

            Map<String, Object> unit = (Map<String, Object>) entry.getValue().get("unit");
            int unitCount = ((Number) unit.get("count")).intValue();
            double unitDuration = ((Number) unit.get("time")).doubleValue();

            Technology technology = new Technology(name, unitCount, unitDuration);

            technology.prerequisites.addAll(prerequisites);

            List<List<Object>> ingredients = (List<List<Object>>) unit.get("ingredients");
            for (List<Object> ingredient : ingredients) {
                technology.unitIngredients.put((String) ingredient.get(0), (Number) ingredient.get(1));
            }

            List<Map<String, Object>> effects = (List<Map<String, Object>>) entry.getValue().get("effects");
            if (null == effects) {
                effects = new ArrayList<>();
            }
            for (Map<String, Object> effect : effects) {
                String type = (String) effect.get("type");
                switch (type) {
                    case "unlock-recipe":
                        technology.recipesUnlocked.add((String) effect.get("recipe"));
                        recipeTechnologyMap.put((String) effect.get("recipe"), technology);
                        if (unlockedRecipes.contains(effect.get("recipe"))) {
                            // wtf!?
                            System.err.println("Recipe " + effect.get("recipe") + " is enabled, but unlocked by technology " + name + "!?");
                        }
                        break;
                    case "laboratory-speed":
                        technology.modifiers.put(type, (Number) effect.get("modifier"));
                        break;
                    default:
                        System.err.println("Do we care about " + type + "?");
                        //fall-through
                    case "ammo-damage":
                    case "auto-character-logistic-trash-slots":
                    case "character-logistic-slots":
                    case "character-logistic-trash-slots":
                    case "ghost-time-to-live":
                    case "gun-speed":
                    case "inserter-stack-size-bonus":
                    case "maximum-following-robots-count":
                    case "num-quick-bars":
                    case "stack-inserter-capacity-bonus":
                    case "turret-attack":
                    case "worker-robot-speed":
                    case "worker-robot-storage":
                        // don't care
                        break;
                }
            }

            technologies.put(name, technology);
        }
    }

    @SuppressWarnings("unchecked")
    private static void ParseRecipes(Map<String, Map<String, Object>> recipeMap) {
        for (Map.Entry<String, Map<String, Object>> entry : recipeMap.entrySet()) {
            String name = entry.getKey();
            String category = (String) entry.getValue().get("category");
            if (null == category) {
                category = "crafting";
            }
            Number craftingTime = (Number) entry.getValue().get("energy_required");
            if (null == craftingTime) {
                craftingTime = 0.5;
            }
            String result = (String) entry.getValue().get("result");
            Recipe recipe = new Recipe(name, category, craftingTime.doubleValue());
            if (null != result) {
                Number amount = (Number) entry.getValue().get("result_count");
                if (null == amount) {
                    amount = 1;
                }
                recipe.results.put(result, amount);
            } else {
                Object resultsObject = entry.getValue().get("results");
                List<Map<String, Object>> resultsList;
                if (resultsObject instanceof Map) {
                    resultsList = new ArrayList<>();
                    resultsList.add((Map<String, Object>) resultsObject);
                } else if (resultsObject instanceof List) {
                    resultsList = (List<Map<String, Object>>) resultsObject;
                } else {
                    // wtf!?
                    continue;
                }
                for (Map<String, Object> results : resultsList) {
                    recipe.results.put((String) results.get("name"), (Number) results.get("amount"));
                }
            }
            List<Object> ingredients = (List<Object>) entry.getValue().get("ingredients");
            for (Object ingredientRaw : ingredients) {
                if (ingredientRaw instanceof List) {
                    List<Object> ingredient = (List<Object>) ingredientRaw;
                    recipe.ingredients.put((String) ingredient.get(0), (Number) ingredient.get(1));
                } else if (ingredientRaw instanceof Map) {
                    Map<String, Object> ingredient = (Map<String, Object>) ingredientRaw;
                    recipe.ingredients.put((String) ingredient.get("name"), (Number) ingredient.get("amount"));
                }
            }
            recipes.put(name, recipe);

            for (Map.Entry<String, Number> resultEntry : recipe.results.entrySet()) {
                Set<Recipe> recipeSet = resultRecipeMap.get(resultEntry.getKey());
                if (null == recipeSet) {
                    recipeSet = new TreeSet<>();
                    resultRecipeMap.put(resultEntry.getKey(), recipeSet);
                }
                recipeSet.add(recipe);
            }

            Object enabledRaw = entry.getValue().get("enabled");
            boolean enabled;
            if (null == enabledRaw) {
                enabled = true;
            } else if (enabledRaw instanceof Boolean) {
                enabled = (Boolean) enabledRaw;
            } else if (enabledRaw instanceof String) {
                enabled = Boolean.valueOf((String) enabledRaw);
            } else {
                enabled = false; // wtf!?
            }

            if (enabled) {
                unlockedRecipes.add(name);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void ParseResources(Map<String, Object> playerMap, Map<String, Map<String, Object>> resourceMap, Map<String, Map<String, Object>> miningDrillMap) {
        for (Map<String, Object> value : resourceMap.values()) {
            String category = (String) value.get("category");
            if (null == category) {
                category = "basic-solid";
            }
            Map<String, Object> minable = (Map<String, Object>) value.get("minable");
            String result = (String) minable.get("result");
            if (null != result) {
                Resource resource = new Resource(result, category, ((Number) minable.get("mining_time")).doubleValue());
                resources.put(result, resource);
            } else {
                List<Map<String, Object>> results = (List<Map<String, Object>>) minable.get("results");
                if (null != results) {
                    for (Map<String, Object> resultMap : results) {
                        result = (String) resultMap.get("name");
                        Resource resource = new Resource(result, category, ((Number) minable.get("mining_time")).doubleValue());
                        resources.put(result, resource);
                    }
                }
            }
        }

        Map<String, List<String>> miningCategories = new HashMap<>();
        Map<String, Double> miningSpeeds = new HashMap<>();
        Object playerMiningCategories = playerMap.get("mining_categories");
        if (playerMiningCategories instanceof List) {
            miningCategories.put((String) playerMap.get("name"), (List<String>) playerMiningCategories);
            miningSpeeds.put((String) playerMap.get("name"), ((Number) playerMap.get("mining_speed")).doubleValue());

        }
        for (Map.Entry<String, Map<String, Object>> entry : miningDrillMap.entrySet()) {
            Object drillResourceCategories = entry.getValue().get("resource_categories");
            if (drillResourceCategories instanceof List) {
                miningCategories.put(entry.getKey(), (List<String>) drillResourceCategories);
            }
            Object drillMiningSpeed = entry.getValue().get("mining_speed");
            if (drillMiningSpeed instanceof Number) {
                miningSpeeds.put(entry.getKey(), ((Number) drillMiningSpeed).doubleValue());
            }
            Object drillModuleSpec = entry.getValue().get("module_specification");
            if (drillModuleSpec != null && drillModuleSpec instanceof Map) {
                int slotCount = ((Map<String, Integer>)drillModuleSpec).get("module_slots");
                moduleSlots.put(entry.getKey(), slotCount);
            }
        }

        for (Map.Entry<String, Double> entry : miningSpeeds.entrySet()) {
            String drillName = entry.getKey();
            double baseSpeed = entry.getValue();
            List<String> categories = miningCategories.get(drillName);
            for (Resource resource : resources.values()) {
                if (!categories.contains(resource.category)) {
                    continue;
                }
                resource.miningTimes.put(drillName, resource.baseMiningTime / baseSpeed);
            }
        }

        // Fake water to make everything else cleaner
        Resource water = new Resource("water", "water", 1);
        water.miningTimes.put("offshore-pump", 1.0);
        resources.put("water", water);

        // Fake wood too
        Resource wood = new Resource("raw-wood", "trees", 2);
        wood.miningTimes.put("player", 2.0);
        resources.put("raw-wood", wood);

        // And alien artifacts
        Resource artifact = new Resource("alien-artifact", "artifacts", 0);
        artifact.miningTimes.put("player", 0.0);
        resources.put("alien-artifact", artifact);
    }

    @SuppressWarnings("unchecked")
    private static void ParseCrafters(Map<String, Object> playerMap, Map<String, Map<String, Object>> assemblingMachineMap,
            Map<String, Map<String, Object>> furnaceMap, Map<String, Map<String, Object>> rocketSiloMap) {
        Crafter player = new Crafter("player", 1000, 1);
        player.categories.addAll((List<String>) playerMap.get("crafting_categories"));
        crafters.put("player", player);

        for (Map.Entry<String, Map<String, Object>> entry : assemblingMachineMap.entrySet()) {
            String name = entry.getKey();
            int ingredientCount = ((Number) entry.getValue().get("ingredient_count")).intValue();
            double craftingSpeed = ((Number) entry.getValue().get("crafting_speed")).doubleValue();
            Crafter machine = new Crafter(name, ingredientCount, craftingSpeed);

            machine.categories.addAll((List<String>) entry.getValue().get("crafting_categories"));
            crafters.put(name, machine);

            Object moduleSpec = entry.getValue().get("module_specification");
            if (moduleSpec != null && moduleSpec instanceof Map) {
                int slotCount = ((Map<String, Integer>)moduleSpec).get("module_slots");
                moduleSlots.put(name, slotCount);
            }
        }

        for (Map.Entry<String, Map<String, Object>> entry : furnaceMap.entrySet()) {
            String name = entry.getKey();
            double craftingSpeed = ((Number) entry.getValue().get("crafting_speed")).doubleValue();
            Crafter machine = new Crafter(name, 1, craftingSpeed);

            machine.categories.addAll((List<String>) entry.getValue().get("crafting_categories"));
            crafters.put(name, machine);

            Object moduleSpec = entry.getValue().get("module_specification");
            if (moduleSpec != null && moduleSpec instanceof Map) {
                int slotCount = ((Map<String, Integer>)moduleSpec).get("module_slots");
                moduleSlots.put(name, slotCount);
            }
        }

        for (Map.Entry<String, Map<String, Object>> entry : rocketSiloMap.entrySet()) {
            String name = entry.getKey();
            double craftingSpeed = ((Number) entry.getValue().get("crafting_speed")).doubleValue();
            Crafter machine = new Crafter(name, 1, craftingSpeed);

            machine.categories.addAll((List<String>) entry.getValue().get("crafting_categories"));
            crafters.put(name, machine);

            Object moduleSpec = entry.getValue().get("module_specification");
            if (moduleSpec != null && moduleSpec instanceof Map) {
                int slotCount = ((Map<String, Integer>)moduleSpec).get("module_slots");
                moduleSlots.put(name, slotCount);
            }
        }
    }

    private static Map<Object, Object> GetData() {
        class AutoLuaState extends LuaState implements AutoCloseable {
        }
        try (AutoLuaState luaState = new AutoLuaState()) {
            // First, handle the data loader
            luaState.openLibs();
            try (Stream<Path> coreLuaFiles = GetAllLuaFiles(GetCoreLuaFolder())) {
                coreLuaFiles.forEach((p) -> digestLua(luaState, p));
            }
            digestLua(luaState, Paths.get("C:\\Program Files (x86)\\Steam\\steamapps\\common\\Factorio\\data\\base\\data.lua"));
            luaState.getGlobal("data");
            luaState.checkType(luaState.getTop(), TABLE);
            luaState.getField(luaState.getTop(), "raw");
            return parseTable(luaState, luaState.getTop());
        }
    }

    private static Map<Object, Object> parseTable(LuaState luaState, int index) {
        Map<Object, Object> result = new HashMap<>();
        luaState.pushNil();
        int keyIndex = luaState.getTop(), valueIndex = keyIndex + 1;
        luaState.checkType(index, TABLE);
        while (luaState.next(index)) {
            Object key;
            switch (luaState.type(keyIndex)) {
                case STRING:
                    key = luaState.checkString(keyIndex);
                    break;
                case NUMBER:
                    key = maybeConvertToInt(luaState.toNumberX(keyIndex));
                    break;
                default:
                    System.err.println("Unhandled key type: " + luaState.type(keyIndex).displayText());
                    luaState.pop(1);
                    continue;
            }

            Object value;
            switch (luaState.type(valueIndex)) {
                case TABLE:
                    value = maybeConvertToList(parseTable(luaState, valueIndex));
                    break;
                case STRING:
                    value = luaState.checkString(valueIndex);
                    break;
                case BOOLEAN:
                    value = luaState.toBoolean(valueIndex);
                    break;
                case NUMBER:
                    value = maybeConvertToInt(luaState.toNumberX(valueIndex));
                    break;
                default:
                    System.err.println("Unhandled value type: " + luaState.type(valueIndex).displayText());
                    // fallthrough
                case FUNCTION:
                    // don't care about these
                    luaState.pop(1);
                    continue;
            }
            result.put(key, value);
            luaState.pop(1);
        }
        return result;
    }

    private static Object maybeConvertToInt(Double doubleValue) {
        Object value;
        if (Math.floor(doubleValue) == doubleValue) {
            value = doubleValue.intValue();
        } else {
            value = doubleValue;
        }
        return value;
    }

    private static Object maybeConvertToList(Map<Object, Object> map) {
        for (Map.Entry<Object, Object> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof Integer)) {
                return map;
            }
        }
        List<Object> list = new ArrayList<>();
        for (int i = 1; i <= map.size(); i++) {
            Object value = map.get(i);
            if (null == value) {
                return map;
            }
            list.add(value);
        }
        return list;
    }

    private static Stream<Path> GetAllLuaFiles(Path pathToLua) {
        try {
            return Files.find(pathToLua, Integer.MAX_VALUE, (path, attrs) -> path.toString().toLowerCase().endsWith(".lua"), FileVisitOption.FOLLOW_LINKS);
        } catch (IOException e) {
            e.printStackTrace();
            return Stream.empty();
        }
    }

    private static Path GetCoreLuaFolder() {
        //FileDialog fileDialog = new FileDialog(window, "Choose a folder to look for recipes");
        return Paths.get("C:\\Program Files (x86)\\Steam\\steamapps\\common\\Factorio\\data\\core\\lualib");
    }

    private static void digestLua(LuaState luaState, Path path) {
        try (InputStream inputStream = Files.newInputStream(path, StandardOpenOption.READ)) {
            luaState.load(inputStream, path.getFileName().toString(), "t");
            luaState.call(0, 0);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (LuaRuntimeException e) {
            e.printLuaStackTrace();
        }
    }

    public static abstract class Step extends NamedThing {
        List<String> modules = null;

        protected Step(String name, List<String> modules) {
            super(name);
            this.modules = modules;
        }

        public abstract String toString();
    }

    public static class ResearchStep extends Step {
        Technology technology = null;

        public ResearchStep(Technology technology, List<String> modules) {
            super(technology.name, modules);
            this.technology = technology;
        }
        public String toString() {
            return "Research " + technology.name;
        }
    }

    public static class CraftStep extends Step {
        Recipe recipe = null;
        int craftCount = 0;
        String crafter = null;

        public CraftStep(Recipe recipe, int craftCount, String crafter, List<String> modules) {
            super(recipe.name, modules);
            this.recipe = recipe;
            this.craftCount = craftCount;
            this.crafter = crafter;
        }

        public String toString() {
            String result = "(";
            boolean bFirst = true;
            for (Map.Entry<String, Number> entry : recipe.results.entrySet()) {
                if (!bFirst) {
                    result += ", ";
                } else {
                    bFirst = false;
                }
                result += entry.getValue() + " " + entry.getKey() + (entry.getValue().doubleValue() != 1.0 ? "s" : "");
            }
            result += ")";
            return "Craft " + recipe.name + " " + result + " " + craftCount + " time" + (craftCount != 1 ? "s" : "") + " with " + crafter;
        }
    }

    public static class MineStep extends Step {
        Resource resource = null;
        double resourceCount = 0;
        String miner = null;

        public MineStep(Resource resource, double resourceCount, String miner, List<String> modules) {
            super(resource.name, modules);
            this.resource = resource;
            this.resourceCount = resourceCount;
            this.miner = miner;
        }

        public String toString() {
            return "Mine or pump " + resourceCount + " " + resource.name + " with " + miner;
        }
    }

    public static abstract class NamedThing implements Comparable<NamedThing> {
        public final String name;

        public NamedThing(String name) {
            this.name = name;
        }

        @Override
        public int compareTo(NamedThing o) {
            return name.compareTo(o.name);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static class Resource extends NamedThing {
        public String category;
        public double baseMiningTime;
        public Map<String, Double> miningTimes;

        public Resource(String name, String category, double baseMiningTime) {
            super(name);
            this.category = category;
            this.baseMiningTime = baseMiningTime;
            miningTimes = new HashMap<>();
        }
    }

    public static class Crafter extends NamedThing {
        public Set<String> categories;
        public int ingredientCount;
        public double craftingSpeed;

        public Crafter(String name, int ingredientCount, double craftingSpeed) {
            super(name);
            this.ingredientCount = ingredientCount;
            this.craftingSpeed = craftingSpeed;
            categories = new HashSet<>();
        }
    }

    public static class Recipe extends NamedThing {
        public String category;
        public double craftingTime;
        public Map<String, Number> ingredients;
        public Map<String, Number> results;

        public Recipe(String name, String category, double craftingTime) {
            super(name);
            this.category = category;
            this.craftingTime = craftingTime;
            ingredients = new HashMap<>();
            results = new HashMap<>();
        }
    }

    public static class Technology extends NamedThing {
        public int unitCount;
        public double unitDuration;
        public Map<String, Number> unitIngredients;
        public Set<String> prerequisites;
        public Set<String> recipesUnlocked;
        public Map<String, Number> modifiers;

        public Technology(String name, int unitCount, double unitDuration) {
            super(name);
            this.unitCount = unitCount;
            this.unitDuration = unitDuration;
            unitIngredients = new HashMap<>();
            prerequisites = new HashSet<>();
            recipesUnlocked = new HashSet<>();
            modifiers = new HashMap<>();
        }
    }

    public static class Module extends NamedThing {
        public Map<String, Double> effects;
        public Set<String> limitations;

        public Module(String name, Map<String, Double> effects, Set<String> limitations) {
            super(name);
            this.effects = effects;
            this.limitations = limitations;
        }
    }
}
