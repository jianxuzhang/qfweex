/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.taobao.weex.devtools;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import com.taobao.weex.WXSDKManager;
import com.taobao.weex.devtools.adapter.JsLogAdapter;
import com.taobao.weex.devtools.adapter.WXTracingAdapter;
import com.taobao.weex.devtools.common.LogUtil;
import com.taobao.weex.devtools.debug.IWebSocketClient;
import com.taobao.weex.devtools.inspector.console.RuntimeReplFactory;
import com.taobao.weex.devtools.inspector.elements.Document;
import com.taobao.weex.devtools.inspector.elements.DocumentProviderFactory;
import com.taobao.weex.devtools.inspector.elements.android.ActivityTracker;
import com.taobao.weex.devtools.inspector.elements.android.AndroidDocumentConstants;
import com.taobao.weex.devtools.inspector.elements.android.AndroidDocumentProviderFactory;
import com.taobao.weex.devtools.inspector.protocol.ChromeDevtoolsDomain;
import com.taobao.weex.devtools.inspector.protocol.module.CSS;
import com.taobao.weex.devtools.inspector.protocol.module.Console;
import com.taobao.weex.devtools.inspector.protocol.module.DOM;
import com.taobao.weex.devtools.inspector.protocol.module.Debugger;
import com.taobao.weex.devtools.inspector.protocol.module.Input;
import com.taobao.weex.devtools.inspector.protocol.module.Inspector;
import com.taobao.weex.devtools.inspector.protocol.module.Network;
import com.taobao.weex.devtools.inspector.protocol.module.Page;
import com.taobao.weex.devtools.inspector.protocol.module.Runtime;
import com.taobao.weex.devtools.inspector.protocol.module.Worker;
import com.taobao.weex.devtools.inspector.protocol.module.WxDebug;
import com.taobao.weex.devtools.inspector.runtime.RhinoDetectingRuntimeReplFactory;
import com.taobao.weex.utils.WXLogUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

/**
 * Initialization and configuration entry point for the WeexInspector debugging system.  Simple usage with
 * default plugins and features enabled:
 * <p>
 * <pre>
 *   WeexInspector.initializeWithDefaults(context)
 * </pre>
 * <p>
 * For more advanced configuration, see {@link #newInitializerBuilder(Context)} or
 * the {@code stetho-sample} for more information.
 */
public class WeexInspector {
  private static IWebSocketClient customerWSClient;
  private static volatile AtomicBoolean sInited = new AtomicBoolean(false);
  private WeexInspector() {
  }

  /**
   * Construct a simple initializer helper which allows you to customize stetho behaviour
   * with additional features, plugins, etc.  See DefaultDumperPluginsBuilder and
   * {@link DefaultInspectorModulesBuilder} for more information.
   * <p>
   * For simple use cases, consider {@link #initializeWithDefaults(Context)}.
   */
  public static InitializerBuilder newInitializerBuilder(Context context) {
    return new InitializerBuilder(context);
  }

  /**
   * Start the listening server.  Most of the heavy lifting initialization is deferred until the
   * first socket connection is received, allowing this to be safely used for debug builds on
   * even low-end hardware without noticeably affecting performance.
   */
  public static void initializeWithDefaults(final Context context) {
    initialize(new Initializer(context) {
      @Override
      protected Iterable<ChromeDevtoolsDomain> getInspectorModules() {
        return new DefaultInspectorModulesBuilder(context).finish();
      }
    });
  }

  /**
   * Start the listening service, providing a custom initializer as per
   * {@link #newInitializerBuilder}.
   *
   * @see #initializeWithDefaults(Context)
   */
  public static void initialize(final Initializer initializer) {
    // Hook activity tracking so that after WeexInspector is attached we can figure out what
    // activities are present.
    if (sInited.get()) {
      LogUtil.w("WeexInspector initialized");
      return;
    }
    boolean isTrackingActivities = ActivityTracker.get().beginTrackingIfPossible(
        (Application)initializer.mContext.getApplicationContext());

    sInited.set(isTrackingActivities);
    if (!isTrackingActivities) {
      LogUtil.w("Automatic activity tracking not available on this API level, caller must invoke " +
          "ActivityTracker methods manually!");
    }
  }

  public static void initToolbox() {
    try {
      WXSDKManager.getInstance().setTracingAdapter(new WXTracingAdapter());
      WXLogUtils.setJsLogWatcher(JsLogAdapter.getInstance());
    } catch (Throwable throwable) {
      throwable.printStackTrace();
    }
  }

  public static InspectorModulesProvider defaultInspectorModulesProvider(final Context context) {
    return new InspectorModulesProvider() {
      @Override
      public Iterable<ChromeDevtoolsDomain> get() {
        return new DefaultInspectorModulesBuilder(context).finish();
      }
    };
  }

  private static class PluginBuilder<T> {
    private final Set<String> mProvidedNames = new HashSet<>();
    private final Set<String> mRemovedNames = new HashSet<>();

    private final ArrayList<T> mPlugins = new ArrayList<>();

    private boolean mFinished;

    public void provide(String name, T plugin) {
      throwIfFinished();
      mPlugins.add(plugin);
      mProvidedNames.add(name);
    }

    public void provideIfDesired(String name, T plugin) {
      throwIfFinished();
      if (!mRemovedNames.contains(name)) {
        if (mProvidedNames.add(name)) {
          mPlugins.add(plugin);
        }
      }
    }

    public void remove(String pluginName) {
      throwIfFinished();
      mRemovedNames.remove(pluginName);
    }

    private void throwIfFinished() {
      if (mFinished) {
        throw new IllegalStateException("Must not continue to build after finish()");
      }
    }

    public Iterable<T> finish() {
      mFinished = true;
      return mPlugins;
    }
  }

  /**
   * Configuration mechanism to customize the behaviour of the standard set of inspector
   * modules satisfying the Chrome DevTools protocol.  Note that while it is still technically
   * possible to manually control these modules, this API is strongly discouraged and will not
   * necessarily be supported in future releases.
   */
  public static final class DefaultInspectorModulesBuilder {
    private final Application mContext;
    private final PluginBuilder<ChromeDevtoolsDomain> mDelegate = new PluginBuilder<>();


    @Nullable private DocumentProviderFactory mDocumentProvider;
    @Nullable private RuntimeReplFactory mRuntimeRepl;

    public DefaultInspectorModulesBuilder(Context context) {
      mContext = (Application)context.getApplicationContext();
    }

    /**
     * Provide a custom document provider factory which can operate on the logical DOM exposed to
     * Chrome in the Elements tab.  An Android View hierarchy instance is provided by
     * default if this method is not called.
     * <p>
     * <i>Experimental.</i>  This API may be changed or removed in the future.
     */
    public DefaultInspectorModulesBuilder documentProvider(DocumentProviderFactory factory) {
      mDocumentProvider = factory;
      return this;
    }

    /**
     * Provide a custom runtime REPL (read-eval-print loop) implementation for the Console tab.
     * By default an implementation will be provided for you that automatically detects
     * the existence of {@code stetho-js-rhino} (Mozilla's Rhino engine) and uses it if available.
     * <p>
     * To customize the Rhino implementation, see {@code stetho-js-rhino} documentation.
     */
    public DefaultInspectorModulesBuilder runtimeRepl(RuntimeReplFactory factory) {
      mRuntimeRepl = factory;
      return this;
    }

    /**
     * Provide either a new domain module or override an existing one.
     *
     * @deprecated This fine-grained control of the devtools modules is no longer supportable
     *     given the lack of isolation of modules in the actual protocol (many cross dependencies
     *     emerge when you implement more and more of the real protocol).
     */
    @Deprecated
    public DefaultInspectorModulesBuilder provide(ChromeDevtoolsDomain module) {
      mDelegate.provide(module.getClass().getName(), module);
      return this;
    }

    private DefaultInspectorModulesBuilder provideIfDesired(ChromeDevtoolsDomain module) {
      mDelegate.provideIfDesired(module.getClass().getName(), module);
      return this;
    }

    /**
     * Remove an existing domain module.
     *
     * @deprecated This fine-grained control of the devtools modules is no longer supportable
     *     given the lack of isolation of modules in the actual protocol (many cross dependencies
     *     emerge when you implement more and more of the real protocol).
     */
    @Deprecated
    public DefaultInspectorModulesBuilder remove(String moduleName) {
      mDelegate.remove(moduleName);
      return this;
    }

    public Iterable<ChromeDevtoolsDomain> finish() {
      provideIfDesired(new Console());
      provideIfDesired(new Debugger());
      provideIfDesired(new WxDebug());
      DocumentProviderFactory documentModel = resolveDocumentProvider();
      if (documentModel != null) {
        Document document = new Document(documentModel);
        provideIfDesired(new DOM(document));
        provideIfDesired(new CSS(document));
      }
      provideIfDesired(new Input());
      provideIfDesired(new Inspector());
      provideIfDesired(new Network(mContext));
      provideIfDesired(new Page(mContext));
      provideIfDesired(
          new Runtime(
              mRuntimeRepl != null ?
              mRuntimeRepl :
              new RhinoDetectingRuntimeReplFactory(mContext)));
      provideIfDesired(new Worker());
      return mDelegate.finish();
    }

    @Nullable
    private DocumentProviderFactory resolveDocumentProvider() {
      if (mDocumentProvider != null) {
        return mDocumentProvider;
      }
      if (Build.VERSION.SDK_INT >= AndroidDocumentConstants.MIN_API_LEVEL) {
        return new AndroidDocumentProviderFactory(mContext);
      }
      return null;
    }
  }

  public static void overrideWebSocketClient(IWebSocketClient webSocketClient) {
    WeexInspector.customerWSClient = webSocketClient;
  }

  public static IWebSocketClient getCustomerWSClient() {
    return WeexInspector.customerWSClient;
  }

  /**
   * Callers can choose to subclass this directly to provide the initialization configuration
   * or they can construct a concrete instance using {@link #newInitializerBuilder(Context)}.
   */
  public static abstract class Initializer {
    private final Context mContext;

    protected Initializer(Context context) {
      mContext = context.getApplicationContext();
    }

    @Nullable
    protected abstract Iterable<ChromeDevtoolsDomain> getInspectorModules();

  }

  /**
   * Configure what services are to be enabled in this instance of WeexInspector.
   */
  public static class InitializerBuilder {
    protected final Context mContext;

    // @Nullable DumperPluginsProvider mDumperPlugins;
    @Nullable InspectorModulesProvider mInspectorModules;

    private InitializerBuilder(Context context) {
      mContext = context.getApplicationContext();
    }

    public InitializerBuilder enableWebKitInspector(InspectorModulesProvider modules) {
      mInspectorModules = modules;
      return this;
    }

    public Initializer build() {
      return new BuilderBasedInitializer(this);
    }
  }

  private static class BuilderBasedInitializer extends Initializer {
    @Nullable private final InspectorModulesProvider mInspectorModules;

    private BuilderBasedInitializer(InitializerBuilder b) {
      super(b.mContext);
      mInspectorModules = b.mInspectorModules;
    }

    @Nullable
    @Override
    protected Iterable<ChromeDevtoolsDomain> getInspectorModules() {
      return mInspectorModules != null ? mInspectorModules.get() : null;
    }
  }
}
