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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class Main {

    public static void main(String[] args) {
        Map<Object, Object> data = GetData();

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
}
