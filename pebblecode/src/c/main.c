#include <pebble.h>

#define KEY_CMD 0
#define KEY_STATE 1
#define KEY_MESSAGE 2
#define KEY_DESIRED 3

#define CMD_STATUS 1
#define CMD_TOGGLE 2
#define CMD_SET 3
#define CMD_RESULT 4
#define CMD_ERROR 5

#define DESIRED_OFF 0
#define DESIRED_ON 1

static Window *s_window;
static Layer *s_dial_layer;
static TextLayer *s_title_layer;
static TextLayer *s_state_layer;
static TextLayer *s_message_layer;
static char s_state[24] = "Unknown";
static char s_message[96] = "Ready";
static bool s_waiting;

static GColor color_bg(void) {
#ifdef PBL_COLOR
  return GColorBlack;
#else
  return GColorWhite;
#endif
}

static GColor color_panel(void) {
#ifdef PBL_COLOR
  return GColorDarkGray;
#else
  return GColorWhite;
#endif
}

static GColor color_text(void) {
#ifdef PBL_COLOR
  return GColorWhite;
#else
  return GColorBlack;
#endif
}

static GColor color_muted(void) {
#ifdef PBL_COLOR
  return GColorLightGray;
#else
  return GColorDarkGray;
#endif
}

static GColor color_state(void) {
  if (strcmp(s_state, "On") == 0) {
    return PBL_IF_COLOR_ELSE(GColorJaegerGreen, GColorBlack);
  }
  if (strcmp(s_state, "Off") == 0) {
    return PBL_IF_COLOR_ELSE(GColorSunsetOrange, GColorBlack);
  }
  if (strcmp(s_state, "Working") == 0) {
    return PBL_IF_COLOR_ELSE(GColorChromeYellow, GColorBlack);
  }
  if (strcmp(s_state, "Error") == 0) {
    return PBL_IF_COLOR_ELSE(GColorRed, GColorBlack);
  }
  return color_text();
}

static void copy_text(char *dest, size_t size, const char *source) {
  if (!dest || size == 0) {
    return;
  }
  if (!source) {
    source = "";
  }
  snprintf(dest, size, "%s", source);
}

static void update_layers(void) {
  text_layer_set_text(s_state_layer, s_state);
  text_layer_set_text_color(s_state_layer, color_state());
  text_layer_set_text(s_message_layer, s_message);
  if (s_dial_layer) {
    layer_mark_dirty(s_dial_layer);
  }
}

static void dial_update_proc(Layer *layer, GContext *ctx) {
  GRect bounds = layer_get_bounds(layer);
  GPoint center = grect_center_point(&bounds);
  int16_t radius = bounds.size.w < bounds.size.h ? bounds.size.w / 2 - 8 : bounds.size.h / 2 - 8;

  graphics_context_set_fill_color(ctx, color_panel());
  graphics_fill_circle(ctx, center, radius);

#ifdef PBL_COLOR
  GColor accent = color_state();
  graphics_context_set_stroke_color(ctx, accent);
  graphics_context_set_stroke_width(ctx, 6);
  graphics_draw_circle(ctx, center, radius - 3);
  graphics_context_set_fill_color(ctx, accent);
  if (s_waiting) {
    graphics_fill_circle(ctx, GPoint(center.x, center.y - radius + 11), 5);
    graphics_fill_circle(ctx, GPoint(center.x + radius - 11, center.y), 5);
    graphics_fill_circle(ctx, GPoint(center.x, center.y + radius - 11), 5);
  } else if (strcmp(s_state, "On") == 0) {
    graphics_fill_circle(ctx, center, radius / 3);
  } else if (strcmp(s_state, "Off") == 0) {
    graphics_context_set_stroke_width(ctx, 5);
    graphics_draw_line(ctx, GPoint(center.x - radius / 3, center.y + radius / 3),
                       GPoint(center.x + radius / 3, center.y - radius / 3));
  }
#else
  graphics_context_set_stroke_color(ctx, GColorBlack);
  graphics_context_set_stroke_width(ctx, 3);
  graphics_draw_circle(ctx, center, radius - 3);
#endif
}

static void send_command(int command, int desired) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK || !iter) {
    copy_text(s_state, sizeof(s_state), "Error");
    copy_text(s_message, sizeof(s_message), "Phone bridge unavailable");
    update_layers();
    return;
  }

  dict_write_int(iter, KEY_CMD, &command, sizeof(command), true);
  if (command == CMD_SET) {
    dict_write_int(iter, KEY_DESIRED, &desired, sizeof(desired), true);
  }
  dict_write_end(iter);

  s_waiting = true;
  copy_text(s_state, sizeof(s_state), "Working");
  if (command == CMD_SET && desired == DESIRED_ON) {
    copy_text(s_message, sizeof(s_message), "Connecting");
  } else if (command == CMD_SET && desired == DESIRED_OFF) {
    copy_text(s_message, sizeof(s_message), "Disconnecting");
  } else if (command == CMD_TOGGLE) {
    copy_text(s_message, sizeof(s_message), "Toggling");
  } else {
    copy_text(s_message, sizeof(s_message), "Checking");
  }
  update_layers();
  app_message_outbox_send();
}

static int tuple_int(DictionaryIterator *iter, uint32_t key, int fallback) {
  Tuple *tuple = dict_find(iter, key);
  if (!tuple) {
    return fallback;
  }
  return (int)tuple->value->int32;
}

static const char *tuple_string(DictionaryIterator *iter, uint32_t key, const char *fallback) {
  Tuple *tuple = dict_find(iter, key);
  if (!tuple || !tuple->value->cstring) {
    return fallback;
  }
  return tuple->value->cstring;
}

static void inbox_received_callback(DictionaryIterator *iter, void *context) {
  int command = tuple_int(iter, KEY_CMD, 0);
  if (command == CMD_RESULT) {
    copy_text(s_state, sizeof(s_state), tuple_string(iter, KEY_STATE, "Unknown"));
    copy_text(s_message, sizeof(s_message), tuple_string(iter, KEY_MESSAGE, ""));
    s_waiting = false;
    update_layers();
  } else if (command == CMD_ERROR) {
    copy_text(s_state, sizeof(s_state), "Error");
    copy_text(s_message, sizeof(s_message), tuple_string(iter, KEY_MESSAGE, "Unknown error"));
    s_waiting = false;
    update_layers();
  }
}

static void inbox_dropped_callback(AppMessageResult reason, void *context) {
  copy_text(s_state, sizeof(s_state), "Error");
  copy_text(s_message, sizeof(s_message), "Phone reply was dropped");
  s_waiting = false;
  update_layers();
}

static void outbox_failed_callback(DictionaryIterator *iter, AppMessageResult reason, void *context) {
  copy_text(s_state, sizeof(s_state), "Error");
  copy_text(s_message, sizeof(s_message), "Could not reach phone bridge");
  s_waiting = false;
  update_layers();
}

static void select_click_handler(ClickRecognizerRef recognizer, void *context) {
  send_command(CMD_TOGGLE, 0);
}

static void up_click_handler(ClickRecognizerRef recognizer, void *context) {
  send_command(CMD_SET, DESIRED_ON);
}

static void down_click_handler(ClickRecognizerRef recognizer, void *context) {
  send_command(CMD_SET, DESIRED_OFF);
}

static void click_config_provider(void *context) {
  window_single_click_subscribe(BUTTON_ID_SELECT, select_click_handler);
  window_single_click_subscribe(BUTTON_ID_UP, up_click_handler);
  window_single_click_subscribe(BUTTON_ID_DOWN, down_click_handler);
}

static TextLayer *make_text_layer(GRect frame, GFont font, GTextAlignment alignment, GColor text, GColor background) {
  TextLayer *layer = text_layer_create(frame);
  text_layer_set_background_color(layer, background);
  text_layer_set_text_color(layer, text);
  text_layer_set_font(layer, font);
  text_layer_set_text_alignment(layer, alignment);
  return layer;
}

static void window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);
  window_set_background_color(window, color_bg());

  s_dial_layer = layer_create(GRect(8, 18, bounds.size.w - 16, bounds.size.w - 16));
  layer_set_update_proc(s_dial_layer, dial_update_proc);
  layer_add_child(root, s_dial_layer);

  s_title_layer = make_text_layer(GRect(8, 8, bounds.size.w - 16, 22),
                                  fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
                                  GTextAlignmentCenter, color_text(), color_bg());
  text_layer_set_text(s_title_layer, "TAILSCALE");
  layer_add_child(root, text_layer_get_layer(s_title_layer));

  s_state_layer = make_text_layer(GRect(8, 56, bounds.size.w - 16, 46),
                                  fonts_get_system_font(FONT_KEY_BITHAM_42_BOLD),
                                  GTextAlignmentCenter, color_state(), GColorClear);
  layer_add_child(root, text_layer_get_layer(s_state_layer));

  s_message_layer = make_text_layer(GRect(10, bounds.size.h - 38, bounds.size.w - 20, 32),
                                    fonts_get_system_font(FONT_KEY_GOTHIC_18),
                                    GTextAlignmentCenter, color_muted(), color_bg());
  text_layer_enable_screen_text_flow_and_paging(s_message_layer, 2);
  layer_add_child(root, text_layer_get_layer(s_message_layer));

  update_layers();
}

static void window_unload(Window *window) {
  layer_destroy(s_dial_layer);
  text_layer_destroy(s_title_layer);
  text_layer_destroy(s_state_layer);
  text_layer_destroy(s_message_layer);
}

static void init(void) {
  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = window_load,
    .unload = window_unload
  });
  window_set_click_config_provider(s_window, click_config_provider);

  app_message_register_inbox_received(inbox_received_callback);
  app_message_register_inbox_dropped(inbox_dropped_callback);
  app_message_register_outbox_failed(outbox_failed_callback);
  app_message_open(512, 512);

  window_stack_push(s_window, true);
  send_command(CMD_STATUS, 0);
}

static void deinit(void) {
  window_destroy(s_window);
}

int main(void) {
  init();
  app_event_loop();
  deinit();
}
