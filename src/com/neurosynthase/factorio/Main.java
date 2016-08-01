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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Main {
    public static Map<String, Resource> resources = new TreeMap<>();
    public static Map<String, Crafter> crafters = new TreeMap<>();
    public static Map<String, Recipe> recipes = new TreeMap<>();
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
        inventory.put("iron-plate", 8);

        Set<Technology> unlockedTechnologies = new TreeSet<>();

        Map<String, Number> goal = new TreeMap<>();
        goal.put("iron-axe", 1);
        goal.put("science-pack-1", 30);

        List<Step> steps = new ArrayList<>();
        if (solve(inventory, unlockedTechnologies, goal, steps)) {
            System.out.println(String.join("\n", steps.stream().map(Step::toString).collect(Collectors.toList())));
        } else {
            System.out.println("Impossible!");
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
                boolean haveMiner = false;
                for (String miner : resource.miningTimes.keySet()) {
                    if (inventory.containsKey(miner) && inventory.get(miner).intValue() > 0) {
                        haveMiner = true;
                        break;
                    }
                }
                if (!haveMiner) {
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
                            haveMiner = true;
                        }

                        intermediateGoals.remove(miner);
                        if (haveMiner) {
                            break;
                        }
                    }
                    if (!haveMiner) {
                        // Uh oh! Guess this is impossible...
                        return false;
                    }
                }
                stepsSoFar.add(new Step(resources.get(item), need));
                if (inventory.containsKey(item)) {
                    inventory.put(item, inventory.get(item).doubleValue() + need);
                } else {
                    inventory.put(item, need);
                }
                return solve(inventory, unlockedTechnologies, goal, stepsSoFar);
            }
            if (resultRecipeMap.containsKey(item)) {
                Set<Recipe> candidateRecipes = resultRecipeMap.get(item);
                // First pass to see if any are immediately viable
                for (Recipe recipe : candidateRecipes) {
                    if (!unlockedRecipes.contains(recipe.name) && !unlockedTechnologies.contains(recipeTechnologyMap.get(recipe.name))) {
                        // Not unlocked
                        continue;
                    }

                    boolean haveCrafter = false;
                    for (Crafter crafter : crafters.values()) {
                        if (!inventory.containsKey(crafter.name)) {
                            continue;
                        }
                        if (crafter.categories.contains(recipe.category)) {
                            // We can craft it!
                            haveCrafter = true;
                        }
                    }
                    if (!haveCrafter) {
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
                                haveCrafter = true;
                            }

                            intermediateGoals.remove(crafter.name);
                            if (haveCrafter) {
                                break;
                            }
                        }
                        if (!haveCrafter) {
                            // Uh oh! Guess this is impossible...
                            return false;
                        }
                    }
                    int count = (int) Math.ceil(need / recipe.results.get(item).doubleValue());

                    Map<String, Number> newGoal = new TreeMap<>();
                    for (Map.Entry<String, Number> ingredientEntry : recipe.ingredients.entrySet()) {
                        newGoal.put(ingredientEntry.getKey(), ingredientEntry.getValue().doubleValue() * count);
                    }
                    if (!solve(inventory, unlockedTechnologies, newGoal, stepsSoFar)) {
                        // This recipe won't help us... (might have wasted making a crafter, oh well)
                        continue;
                    }

                    stepsSoFar.add(new Step(recipe, count));
                    for (Map.Entry<String, Number> madeEntry : newGoal.entrySet()) {
                        double remaining = inventory.get(madeEntry.getKey()).doubleValue();
                        remaining -= madeEntry.getValue().doubleValue();
                        if (0 == remaining) {
                            inventory.remove(madeEntry.getKey());
                        } else {
                            inventory.put(madeEntry.getKey(), remaining);
                        }
                    }
                    for (Map.Entry<String, Number> resultEntry : recipe.results.entrySet()) {
                        if (inventory.containsKey(resultEntry.getKey())) {
                            inventory.put(resultEntry.getKey(), inventory.get(resultEntry.getKey()).doubleValue() + resultEntry.getValue().doubleValue() * count);
                        } else {
                            inventory.put(item, resultEntry.getValue().doubleValue() * count);
                        }
                    }
                    return solve(inventory, unlockedTechnologies, goal, stepsSoFar);
                }
                // Might need a new tech!
                Set<Technology> candidateTechnologies = new TreeSet<>();
                for (Recipe recipe : candidateRecipes) {
                    candidateTechnologies.add(recipeTechnologyMap.get(recipe.name));
                }

            }
            return false; // Can't mine or craft it!
        }
        // Have everything we need!
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
        ParseCrafters(playerMap, assemblingMachineMap, furnaceMap);

        Map<String, Map<String, Object>> recipeMap = (Map<String, Map<String, Object>>) data.get("recipe");
        ParseRecipes(recipeMap);

        Map<String, Map<String, Object>> technologyMap = (Map<String, Map<String, Object>>) data.get("technology");
        ParseTechnologies(technologyMap);
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
                recipe.results.put(result, 1);
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
    }

    @SuppressWarnings("unchecked")
    private static void ParseCrafters(Map<String, Object> playerMap, Map<String, Map<String, Object>> assemblingMachineMap, Map<String, Map<String, Object>> furnaceMap) {
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
        }

        for (Map.Entry<String, Map<String, Object>> entry : furnaceMap.entrySet()) {
            String name = entry.getKey();
            double craftingSpeed = ((Number) entry.getValue().get("crafting_speed")).doubleValue();
            Crafter machine = new Crafter(name, 1, craftingSpeed);

            machine.categories.addAll((List<String>) entry.getValue().get("crafting_categories"));
            crafters.put(name, machine);
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

    public static class Step {
        public final Type type;
        Technology technology = null;
        Recipe recipe = null;
        int craftCount = 0;
        Resource resource = null;
        double resourceCount = 0;

        public Step(Recipe recipe, int craftCount) {
            this.type = Type.RECIPE;
            this.recipe = recipe;
            this.craftCount = craftCount;
        }

        public Step(Technology technology) {
            this.type = Type.TECHNOLOGY;
            this.technology = technology;
        }

        public Step(Resource resource, double resourceCount) {
            this.type = Type.RESOURCE;
            this.resource = resource;
            this.resourceCount = resourceCount;
        }

        public String toString() {
            switch (type) {
                case TECHNOLOGY:
                    return "Research " + technology.name;
                case RECIPE:
                    return "Craft " + recipe.name + " " + craftCount + " times";
                case RESOURCE:
                    return "Mine or pump " + resourceCount + " " + resource.name;
                default:
                    return "wtf!? Error'd step...";
            }
        }

        public enum Type {
            TECHNOLOGY, RECIPE, RESOURCE
        }
    }

    public static class Resource {
        public String name;
        public String category;
        public double baseMiningTime;
        public Map<String, Double> miningTimes;

        public Resource(String name, String category, double baseMiningTime) {
            this.name = name;
            this.category = category;
            this.baseMiningTime = baseMiningTime;
            miningTimes = new HashMap<>();
        }
    }

    public static class Crafter {
        public String name;
        public Set<String> categories;
        public int ingredientCount;
        public double craftingSpeed;

        public Crafter(String name, int ingredientCount, double craftingSpeed) {
            this.name = name;
            this.ingredientCount = ingredientCount;
            this.craftingSpeed = craftingSpeed;
            categories = new HashSet<>();
        }
    }

    public static class Recipe implements Comparable<Recipe> {
        public String name;
        public String category;
        public double craftingTime;
        public Map<String, Number> ingredients;
        public Map<String, Number> results;

        public Recipe(String name, String category, double craftingTime) {
            this.name = name;
            this.category = category;
            this.craftingTime = craftingTime;
            ingredients = new HashMap<>();
            results = new HashMap<>();
        }

        @Override
        public int compareTo(Recipe o) {
            return name.compareTo(o.name);
        }
    }

    public static class Technology {
        public String name;
        public int unitCount;
        public double unitDuration;
        public Map<String, Number> unitIngredients;
        public Set<String> prerequisites;
        public Set<String> recipesUnlocked;
        public Map<String, Number> modifiers;

        public Technology(String name, int unitCount, double unitDuration) {
            this.name = name;
            this.unitCount = unitCount;
            this.unitDuration = unitDuration;
            unitIngredients = new HashMap<>();
            prerequisites = new HashSet<>();
            recipesUnlocked = new HashSet<>();
            modifiers = new HashMap<>();
        }
    }
}
