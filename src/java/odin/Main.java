package odin;

import clojure.lang.IFn;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;

/**
 * Native-image entry point. The static initializer runs at build time
 * (graal-build-time registers the odin package for build-time init),
 * loading the odin.core namespace while the JAR classpath is available.
 * At runtime, the namespace is already loaded and -main is bound.
 */
public class Main {
    static {
        IFn require = RT.var("clojure.core", "require");
        require.invoke(Symbol.intern("odin.core"));
    }

    public static void main(String[] args) throws Exception {
        Var mainFn = RT.var("odin.core", "-main");
        mainFn.applyTo(RT.seq(args));
    }
}
