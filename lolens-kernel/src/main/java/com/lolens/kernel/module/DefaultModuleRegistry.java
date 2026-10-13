package com.lolens.kernel.module;

import com.lolens.kernel.event.ModuleStateChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 默认模块注册表：注册时校验模块 id 唯一，按依赖拓扑排序执行生命周期。
 */
public class DefaultModuleRegistry implements ModuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultModuleRegistry.class);

    private final Map<String, LolModule> modules = new LinkedHashMap<>();
    private final Map<String, ModuleState> states = new LinkedHashMap<>();
    private ModuleContext context;

    @Override
    public synchronized void register(LolModule module) {
        String id = module.id();
        if (modules.containsKey(id)) {
            throw new IllegalStateException("模块 id 冲突: " + id);
        }
        modules.put(id, module);
        states.put(id, ModuleState.REGISTERED);
        try {
            // 注册即校验依赖无环，避免后续生命周期方法中途失败导致部分加载
            topologicalOrder();
        } catch (IllegalStateException e) {
            modules.remove(id);
            states.remove(id);
            throw new IllegalStateException("模块依赖成环: " + e.getMessage());
        }
        log.info("模块已注册: {} ({})", module.displayName(), id);
    }

    @Override
    public LolModule find(String moduleId) {
        return modules.get(moduleId);
    }

    @Override
    public synchronized List<LolModule> all() {
        return new ArrayList<>(topologicalOrder());
    }

    @Override
    public synchronized void loadAll(ModuleContext base) {
        this.context = base;
        for (LolModule module : topologicalOrder()) {
            // 为每个模块构造独立的上下文，使其 logger 携带模块 id 前缀
            ModuleContext ctx = new DefaultModuleContext(module, base.events(), base.config(),
                    base.services(), base.hotkeys(), base.scheduler());
            try {
                module.onLoad(ctx);
                transition(module, ModuleState.LOADED);
            } catch (Exception e) {
                log.error("模块加载失败: {} - {}", module.id(), e.getMessage(), e);
                transition(module, ModuleState.ERROR);
            }
        }
    }

    @Override
    public synchronized void enableAll() {
        for (LolModule module : topologicalOrder()) {
            if (states.get(module.id()) == ModuleState.ERROR) {
                continue;
            }
            try {
                module.onEnable();
                transition(module, ModuleState.ENABLED);
            } catch (Exception e) {
                log.error("模块启用失败: {} - {}", module.id(), e.getMessage(), e);
                transition(module, ModuleState.ERROR);
            }
        }
    }

    @Override
    public synchronized void disableAll() {
        for (LolModule module : reverseOrder()) {
            // 仅禁用真正启用过的模块：onLoad 失败（ERROR）或未启用的模块不调用 onDisable
            if (states.get(module.id()) != ModuleState.ENABLED) {
                continue;
            }
            try {
                module.onDisable();
                transition(module, ModuleState.DISABLED);
            } catch (Exception e) {
                log.error("模块禁用失败: {} - {}", module.id(), e.getMessage(), e);
            }
        }
    }

    @Override
    public synchronized void unloadAll() {
        for (LolModule module : reverseOrder()) {
            // 仅卸载加载过的模块：ERROR（onLoad 失败）的模块不调用 onUnload
            ModuleState st = states.get(module.id());
            if (st == null || st == ModuleState.ERROR || st == ModuleState.UNLOADED) {
                continue;
            }
            try {
                module.onUnload();
                transition(module, ModuleState.UNLOADED);
            } catch (Exception e) {
                log.error("模块卸载失败: {} - {}", module.id(), e.getMessage(), e);
            }
        }
    }

    private void transition(LolModule module, ModuleState to) {
        ModuleState from = states.get(module.id());
        states.put(module.id(), to);
        if (context != null) {
            context.events().publish(new ModuleStateChanged(module.id(), from, to));
        }
    }

    /** 按 dependencies() 做拓扑排序；循环依赖直接报错。 */
    private List<LolModule> topologicalOrder() {
        List<LolModule> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (LolModule module : modules.values()) {
            visit(module, visited, visiting, result);
        }
        return result;
    }

    private void visit(LolModule module, Set<String> visited, Set<String> visiting, List<LolModule> result) {
        String id = module.id();
        if (visited.contains(id)) {
            return;
        }
        if (visiting.contains(id)) {
            throw new IllegalStateException("模块依赖成环: " + id);
        }
        visiting.add(id);
        for (String depId : module.dependencies()) {
            LolModule dep = modules.get(depId);
            if (dep != null) {
                visit(dep, visited, visiting, result);
            }
        }
        visiting.remove(id);
        visited.add(id);
        result.add(module);
    }

    private List<LolModule> reverseOrder() {
        List<LolModule> order = topologicalOrder();
        List<LolModule> reversed = new ArrayList<>(order);
        java.util.Collections.reverse(reversed);
        return reversed;
    }
}
