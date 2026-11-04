package app.exteraless.plugins.xposed;

import android.os.SystemClock;

import com.chaquo.python.PyObject;
import org.telegram.ui.ActionBar.ActionBar;

import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;

/**
 * before/after-хук: вызывает Python-методы {@code before_hooked_method(param)} и
 * {@code after_hooked_method(param)}. Отсутствующий метод и пустой метод, унаследованный
 * от MethodHook, не вызываются: это решается один раз при регистрации. MethodHookParam передаётся в Python как есть,
 * Chaquopy оборачивает его в прокси (param.thisObject, param.args, param.getResult(),
 * param.setResult(...) доступны из Python).
 *
 * Фильтры считаются на Java-стороне ДО входа в Python: "before"-фильтры гейтят
 * before_hooked_method, "after"-фильтры — after_hooked_method.
 */
public class PyMethodHook extends XC_MethodHook {

    private final String pluginId;
    private static final ThreadLocal<java.util.IdentityHashMap<MethodHookParam, ActionBar.UnreadImageView>> BADGE_DRAWS = ThreadLocal.withInitial(java.util.IdentityHashMap::new);
    private final PyObject handler;
    private final PyObject beforeCall;
    private final PyObject afterCall;
    private final HookStats beforeStats;
    private final HookStats afterStats;
    private final List<HookFilter> beforeFilters;
    private final List<HookFilter> afterFilters;

    public PyMethodHook(String pluginId, PyObject handler) {
        this(pluginId, handler, PRIORITY_DEFAULT, true, true);
    }

    public PyMethodHook(String pluginId, PyObject handler, int priority) {
        this(pluginId, handler, priority, true, true);
    }

    public PyMethodHook(String pluginId, PyObject handler, boolean before, boolean after) {
        this(pluginId, handler, PRIORITY_DEFAULT, before, after);
    }

    public PyMethodHook(String pluginId, PyObject handler, int priority,
                 boolean before, boolean after) {
        this(pluginId, handler, priority, before, after,
                Collections.emptyList(), Collections.emptyList());
    }

    public PyMethodHook(String pluginId, PyObject handler, int priority,
                 List<HookFilter> beforeFilters, List<HookFilter> afterFilters) {
        this(pluginId, handler, priority, true, true, beforeFilters, afterFilters);
    }

    private PyMethodHook(String pluginId, PyObject handler, int priority,
                 boolean before, boolean after,
                 List<HookFilter> beforeFilters, List<HookFilter> afterFilters) {
        super(priority);
        this.pluginId = pluginId;
        this.handler = handler;
        final int beforeId = HookStats.nextId();
        final int afterId = HookStats.nextId();
        this.beforeCall = before && handler != null
                ? XposedHooks.bindHook(handler, "before_hooked_method", beforeId) : null;
        this.afterCall = after && handler != null
                ? XposedHooks.bindHook(handler, "after_hooked_method", afterId) : null;
        this.beforeStats = beforeCall != null ? new HookStats(beforeId, pluginId, "before") : null;
        this.afterStats = afterCall != null ? new HookStats(afterId, pluginId, "after") : null;
        this.beforeFilters = beforeFilters;
        this.afterFilters = afterFilters;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        if (afterCall != null && param.thisObject instanceof ActionBar.UnreadImageView
                && param.method != null && "onDraw".equals(param.method.getName())
                && ownsUnreadBadge(param.thisObject)
                && HookFilter.evaluateAll(afterFilters, param, false)) {
            ActionBar.UnreadImageView view = (ActionBar.UnreadImageView) param.thisObject;
            java.util.IdentityHashMap<MethodHookParam, ActionBar.UnreadImageView> draws = BADGE_DRAWS.get();
            draws.put(param, view);
            view.beginPluginUnreadBadge();
        }
        if (beforeCall != null && HookFilter.evaluateAll(beforeFilters, param, false)) {
            XposedHooks.callPython(pluginId, beforeCall, param, beforeStats);
            HookNumbers.coerce(param);
        }
    }

    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        try {
            if (afterCall != null && HookFilter.evaluateAll(afterFilters, param, true)) {
                XposedHooks.callPython(pluginId, afterCall, param, afterStats);
                HookNumbers.coerce(param);
            }
        } finally {
            java.util.IdentityHashMap<MethodHookParam, ActionBar.UnreadImageView> draws = BADGE_DRAWS.get();
            ActionBar.UnreadImageView view = draws.remove(param);
            if (view != null) {
                view.endPluginUnreadBadge();
            }
        }
    }

    void addTarget(java.lang.reflect.Member member) {
        if (beforeStats != null) {
            beforeStats.addTarget(member);
        }
        if (afterStats != null) {
            afterStats.addTarget(member);
        }
    }

    private PyObject badgePlugin;
    private boolean badgePluginResolved;
    private long lastBadgeCheck = 0L;
    private boolean lastBadgeResult = false;
    private Object lastBadgeTarget = null;

    private boolean ownsUnreadBadge(Object view) {
        long now = SystemClock.elapsedRealtime();
        if (lastBadgeTarget == view && now - lastBadgeCheck < 250) {
            return lastBadgeResult;
        }
        try {
            if (!badgePluginResolved) {
                badgePlugin = handler != null && handler.containsKey("plugin") ? handler.get("plugin") : null;
                badgePluginResolved = true;
            }
            PyObject plugin = badgePlugin;
            if (plugin == null) {
                lastBadgeResult = false;
                lastBadgeCheck = now;
                lastBadgeTarget = view;
                return false;
            }
            PyObject target = plugin.containsKey("current_back_button") ? plugin.get("current_back_button") : null;
            PyObject count = plugin.containsKey("unread_count") ? plugin.get("unread_count") : null;
            boolean res = target != null && target.toJava(Object.class) == view
                    && count != null && count.toInt() > 0;
            lastBadgeResult = res;
            lastBadgeCheck = now;
            lastBadgeTarget = view;
            return res;
        } catch (Throwable ignored) {
            lastBadgeResult = false;
            lastBadgeCheck = now;
            lastBadgeTarget = view;
            return false;
        }
    }
}
