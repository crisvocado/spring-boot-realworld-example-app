package io.spring.infrastructure.logcore;

public class TraceContextHolder {

  private static final ThreadLocal<TraceContext> CONTEXT_HOLDER = new ThreadLocal<>();

  public static void set(TraceContext context) {
    if (context != null) {
      CONTEXT_HOLDER.set(context);
    } else {
      CONTEXT_HOLDER.remove();
    }
  }

  public static TraceContext get() {
    return CONTEXT_HOLDER.get();
  }

  public static void clear() {
    CONTEXT_HOLDER.remove();
  }
}
