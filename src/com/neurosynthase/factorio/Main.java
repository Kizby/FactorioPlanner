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
import java.util.stream.Stream;

public class Main {
    public static Map<String, Resource> resources = new HashMap<>();
    public static Map<String, Crafter> crafters = new HashMap<>();
    public static Map<String, Recipe> recipes = new HashMap<>();
    public static Map<String, Set<Recipe>> resultRecipeMap = new HashMap<>();
    public static List<String> unlockedRecipes = new ArrayList<>();
    public static Map<String, Technology> technologies = new HashMap<>();

    public static void main(String[] args) {
        Map<Object, Object> data = GetData();
        parseData(data);

        technologies.size();
    }

    @SuppressWarnings("unchecked")
    private static void parseData(Map<Object, Object> data) {
        Map<String, Object> playerMap = (Map<String, Object>) data.get("player");
        playerMap = (Map<String, Object>) playerMap.get("player");

        Map<String, Map<String, Object>> resourceMap = (Map<String, Map<String, Object>>) data.get("resource");
        Map<String, Map<String, Object>> miningDrillMap = (Map<String, Map<String, Object>>) data.get("mining-drill");
        ParseResources(playerMap, resourceMap, miningDrillMap);

        Map<String, Map<String, Object>> assemblingMachineMap = (Map<String, Map<String, Object>>) data.get("assembling-machine");
        ParseCrafters(playerMap, assemblingMachineMap);

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
                        break;
                    case "laboratory-speed":
                        technology.modifiers.put(type, (Number) effect.get("modifier"));
                        break;
                    default:
                        System.err.println("Do we care about " + type + "?");
                        //fall-through
                    case "maximum-following-robots-count":
                    case "worker-robot-speed":
                    case "worker-robot-storage":
                    case "turret-attack":
                    case "ghost-time-to-live":
                    case "stack-inserter-capacity-bonus":
                    case "inserter-stack-size-bonus":
                    case "ammo-damage":
                    case "character-logistic-slots":
                    case "character-logistic-trash-slots":
                    case "auto-character-logistic-trash-slots":
                    case "gun-speed":
                    case "num-quick-bars":
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
                    recipeSet = new HashSet<>();
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
    }

    @SuppressWarnings("unchecked")
    private static void ParseCrafters(Map<String, Object> playerMap, Map<String, Map<String, Object>> assemblingMachineMap) {
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

    public static class Recipe {
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
