/*
 * Fork feature: persistent anti-delete storage.
 *
 * TDLib removes deleted messages from its database, so to keep them across
 * chat re-opens we store a lightweight copy (sender, date, text/caption or a
 * textual placeholder for media) in the app's LevelDB and re-inject it into the
 * chat history when it is loaded.
 */
package org.thunderdog.challegram.telegram;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.data.ContentPreview;
import org.thunderdog.challegram.unsorted.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import me.vkryl.leveldb.LevelDB;

public final class DeletedMessagesStore {
  private static final String KEY_PREFIX = "antidel_";

  private DeletedMessagesStore () { }

  private static String chatPrefix (Tdlib tdlib, long chatId) {
    return KEY_PREFIX + tdlib.id() + "_" + chatId + "_";
  }

  private static String key (Tdlib tdlib, long chatId, long messageId) {
    return chatPrefix(tdlib, chatId) + messageId;
  }

  // Saving

  public static void save (Tdlib tdlib, @Nullable TdApi.Message message) {
    if (message == null || message.isOutgoing || message.id == 0) {
      return;
    }
    try {
      JSONObject json = new JSONObject();
      json.put("id", message.id);
      json.put("date", message.date);
      if (message.senderId != null) {
        switch (message.senderId.getConstructor()) {
          case TdApi.MessageSenderUser.CONSTRUCTOR:
            json.put("u", ((TdApi.MessageSenderUser) message.senderId).userId);
            break;
          case TdApi.MessageSenderChat.CONSTRUCTOR:
            json.put("c", ((TdApi.MessageSenderChat) message.senderId).chatId);
            break;
        }
      }
      TdApi.FormattedText text;
      if (message.content != null && message.content.getConstructor() == TdApi.MessageText.CONSTRUCTOR) {
        text = ((TdApi.MessageText) message.content).text;
      } else {
        text = ContentPreview.getChatListPreview(tdlib, message.chatId, message, false).buildFormattedText(false);
      }
      if (text != null) {
        json.put("t", text.text);
        if (text.entities != null && text.entities.length > 0) {
          JSONArray entities = new JSONArray();
          for (TdApi.TextEntity entity : text.entities) {
            if (isSupportedEntity(entity.type)) {
              JSONObject e = new JSONObject();
              e.put("o", entity.offset);
              e.put("l", entity.length);
              e.put("c", entity.type.getConstructor());
              if (entity.type.getConstructor() == TdApi.TextEntityTypeTextUrl.CONSTRUCTOR) {
                e.put("url", ((TdApi.TextEntityTypeTextUrl) entity.type).url);
              }
              entities.put(e);
            }
          }
          json.put("e", entities);
        }
      }
      Settings.instance().pmc().putString(key(tdlib, message.chatId, message.id), json.toString());
    } catch (JSONException e) {
      Log.w("Unable to save deleted message", e);
    }
  }

  private static boolean isSupportedEntity (TdApi.TextEntityType type) {
    switch (type.getConstructor()) {
      case TdApi.TextEntityTypeBold.CONSTRUCTOR:
      case TdApi.TextEntityTypeItalic.CONSTRUCTOR:
      case TdApi.TextEntityTypeUnderline.CONSTRUCTOR:
      case TdApi.TextEntityTypeStrikethrough.CONSTRUCTOR:
      case TdApi.TextEntityTypeSpoiler.CONSTRUCTOR:
      case TdApi.TextEntityTypeCode.CONSTRUCTOR:
      case TdApi.TextEntityTypeUrl.CONSTRUCTOR:
      case TdApi.TextEntityTypeTextUrl.CONSTRUCTOR:
      case TdApi.TextEntityTypeMention.CONSTRUCTOR:
      case TdApi.TextEntityTypeHashtag.CONSTRUCTOR:
      case TdApi.TextEntityTypeEmailAddress.CONSTRUCTOR:
      case TdApi.TextEntityTypePhoneNumber.CONSTRUCTOR:
        return true;
      default:
        return false;
    }
  }

  @Nullable
  private static TdApi.TextEntityType newEntityType (int constructor, @Nullable String url) {
    switch (constructor) {
      case TdApi.TextEntityTypeBold.CONSTRUCTOR: return new TdApi.TextEntityTypeBold();
      case TdApi.TextEntityTypeItalic.CONSTRUCTOR: return new TdApi.TextEntityTypeItalic();
      case TdApi.TextEntityTypeUnderline.CONSTRUCTOR: return new TdApi.TextEntityTypeUnderline();
      case TdApi.TextEntityTypeStrikethrough.CONSTRUCTOR: return new TdApi.TextEntityTypeStrikethrough();
      case TdApi.TextEntityTypeSpoiler.CONSTRUCTOR: return new TdApi.TextEntityTypeSpoiler();
      case TdApi.TextEntityTypeCode.CONSTRUCTOR: return new TdApi.TextEntityTypeCode();
      case TdApi.TextEntityTypeUrl.CONSTRUCTOR: return new TdApi.TextEntityTypeUrl();
      case TdApi.TextEntityTypeTextUrl.CONSTRUCTOR: return url != null ? new TdApi.TextEntityTypeTextUrl(url) : null;
      case TdApi.TextEntityTypeMention.CONSTRUCTOR: return new TdApi.TextEntityTypeMention();
      case TdApi.TextEntityTypeHashtag.CONSTRUCTOR: return new TdApi.TextEntityTypeHashtag();
      case TdApi.TextEntityTypeEmailAddress.CONSTRUCTOR: return new TdApi.TextEntityTypeEmailAddress();
      case TdApi.TextEntityTypePhoneNumber.CONSTRUCTOR: return new TdApi.TextEntityTypePhoneNumber();
      default: return null;
    }
  }

  // Loading

  @Nullable
  private static TdApi.Message parse (long chatId, String value) {
    try {
      JSONObject json = new JSONObject(value);
      TdApi.Message message = new TdApi.Message();
      message.id = json.getLong("id");
      message.chatId = chatId;
      message.date = json.optInt("date", 0);
      message.isOutgoing = false;
      if (json.has("u")) {
        message.senderId = new TdApi.MessageSenderUser(json.getLong("u"));
      } else if (json.has("c")) {
        message.senderId = new TdApi.MessageSenderChat(json.getLong("c"));
      } else {
        message.senderId = new TdApi.MessageSenderChat(chatId);
      }
      String text = json.optString("t", "");
      List<TdApi.TextEntity> entities = new ArrayList<>();
      JSONArray array = json.optJSONArray("e");
      if (array != null) {
        for (int i = 0; i < array.length(); i++) {
          JSONObject e = array.getJSONObject(i);
          TdApi.TextEntityType type = newEntityType(e.getInt("c"), e.optString("url", null));
          if (type != null) {
            entities.add(new TdApi.TextEntity(e.getInt("o"), e.getInt("l"), type));
          }
        }
      }
      TdApi.MessageText content = new TdApi.MessageText();
      content.text = new TdApi.FormattedText(text, entities.toArray(new TdApi.TextEntity[0]));
      message.content = content;
      return message;
    } catch (JSONException | RuntimeException e) {
      Log.w("Unable to parse deleted message", e);
      return null;
    }
  }

  /**
   * @return all stored deleted messages of the chat, sorted by id ascending
   */
  @NonNull
  public static List<TdApi.Message> load (Tdlib tdlib, long chatId) {
    List<TdApi.Message> result = null;
    for (LevelDB.Entry entry : Settings.instance().pmc().find(chatPrefix(tdlib, chatId))) {
      TdApi.Message message = parse(chatId, entry.asString());
      if (message != null) {
        if (result == null) {
          result = new ArrayList<>();
        }
        result.add(message);
      }
    }
    if (result == null) {
      return Collections.emptyList();
    }
    Collections.sort(result, (a, b) -> Long.compare(a.id, b.id));
    return result;
  }

  @NonNull
  public static Set<Long> loadIds (Tdlib tdlib, long chatId) {
    Set<Long> ids = new HashSet<>();
    String prefix = chatPrefix(tdlib, chatId);
    for (LevelDB.Entry entry : Settings.instance().pmc().find(prefix)) {
      try {
        ids.add(Long.parseLong(entry.key().substring(prefix.length())));
      } catch (NumberFormatException ignored) { }
    }
    return ids;
  }

  /**
   * Injects stored deleted messages into a chunk of history received from TDLib.
   *
   * @param messages       chunk as returned by getChatHistory (newest first)
   * @param fromMessageId  from_message_id used for the request (0 = from the latest message)
   * @param offset         offset used for the request
   * @param limit          limit used for the request
   * @param isFinal        false if the chunk came from the local cache only and a network request may follow
   */
  @NonNull
  public static TdApi.Message[] merge (Tdlib tdlib, long chatId, @NonNull TdApi.Message[] messages, long fromMessageId, int offset, int limit, boolean isFinal) {
    List<TdApi.Message> stored = load(tdlib, chatId);
    if (stored.isEmpty()) {
      return messages;
    }
    long lo, hi;
    if (messages.length > 0) {
      lo = Long.MAX_VALUE;
      hi = Long.MIN_VALUE;
      for (TdApi.Message message : messages) {
        lo = Math.min(lo, message.id);
        hi = Math.max(hi, message.id);
      }
    } else {
      lo = hi = fromMessageId;
    }
    boolean endReached = isFinal && messages.length < limit;
    if (fromMessageId == 0 || (offset < 0 && endReached)) {
      hi = Long.MAX_VALUE; // chunk includes the newest message of the chat
    }
    if (offset >= 0 && endReached) {
      lo = 0; // chunk includes the oldest message of the chat
    }
    if (hi <= lo) {
      return messages;
    }

    Set<Long> existing = new HashSet<>(messages.length);
    for (TdApi.Message message : messages) {
      existing.add(message.id);
    }
    List<TdApi.Message> injected = null;
    for (TdApi.Message message : stored) {
      if (message.id > lo && message.id < hi && !existing.contains(message.id)) {
        if (injected == null) {
          injected = new ArrayList<>();
        }
        injected.add(message);
      }
    }
    if (injected == null) {
      return messages;
    }
    List<TdApi.Message> merged = new ArrayList<>(messages.length + injected.size());
    Collections.addAll(merged, messages);
    merged.addAll(injected);
    Collections.sort(merged, (a, b) -> Long.compare(b.id, a.id)); // newest first
    return merged.toArray(new TdApi.Message[0]);
  }

  // Cleanup

  public static int count (Tdlib tdlib) {
    int count = 0;
    for (LevelDB.Entry ignored : Settings.instance().pmc().find(KEY_PREFIX + tdlib.id() + "_")) {
      count++;
    }
    return count;
  }

  public static void clear (Tdlib tdlib) {
    Settings.instance().pmc().removeByPrefix(KEY_PREFIX + tdlib.id() + "_");
  }
}
