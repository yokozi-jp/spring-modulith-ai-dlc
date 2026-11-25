package authorizationfixture;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 持ち主のいるメモの HTTP API。持ち主で絞り忘れた入口と、正しく絞る入口を持つ。
 *
 * <p>要求した人は {@link #USER_HEADER} で受け取る。行はメモリに持ち、{@link #rows} で読む。
 */
@RestController
@RequestMapping(NoteFixtureController.PATH)
public class NoteFixtureController {

  /** メモの API のパス。 */
  public static final String PATH = "/fixture/notes";

  /** 要求した人の ID を送る header。 */
  public static final String USER_HEADER = "X-Fixture-User";

  /** メモの ID ごとのメモ。 */
  private final Map<UUID, Note> notes = new ConcurrentHashMap<>();

  /** 持ち主のメモを入れ、ID を返す。 */
  public UUID add(final String owner, final String text) {
    final UUID id = UUID.randomUUID();
    notes.put(id, new Note(owner, text));
    return id;
  }

  /** すべての行を、比べられる文字列で返す。 */
  public List<String> rows() {
    return notes.entrySet().stream().map(String::valueOf).sorted().toList();
  }

  /** 持ち主で絞らずにメモを返す。他人のメモが見える。 */
  @GetMapping("/leaky/{id}")
  /* package */ ResponseEntity<Note> leakyFind(@PathVariable final UUID id) {
    final Note note = notes.get(id);
    return note == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(note);
  }

  /** 持ち主にだけメモを返し、ほかの人には存在しないメモと同じ 404 を返す。 */
  @GetMapping("/scoped/{id}")
  /* package */ ResponseEntity<Note> scopedFind(
      @PathVariable final UUID id, @RequestHeader(USER_HEADER) final String user) {
    final Note note = notes.get(id);
    return note == null || !note.owner().equals(user)
        ? ResponseEntity.notFound().build()
        : ResponseEntity.ok(note);
  }

  /** メモを書き換えてから持ち主を確かめる。他人には 404 を返すが、他人のメモは書き換わっている。 */
  @PutMapping("/mutating/{id}")
  /* package */ ResponseEntity<Void> mutatingUpdate(
      @PathVariable final UUID id,
      @RequestHeader(USER_HEADER) final String user,
      @RequestBody final String text) {
    final Note note = notes.computeIfPresent(id, (key, old) -> new Note(old.owner(), text));
    return note == null || !note.owner().equals(user)
        ? ResponseEntity.notFound().build()
        : ResponseEntity.noContent().build();
  }

  /** 持ち主を確かめてから書き換える。ほかの人には存在しないメモと同じ 404 を返し、書き換えない。 */
  @PutMapping("/scoped/{id}")
  /* package */ ResponseEntity<Void> scopedUpdate(
      @PathVariable final UUID id,
      @RequestHeader(USER_HEADER) final String user,
      @RequestBody final String text) {
    final Note note = notes.get(id);
    if (note == null || !note.owner().equals(user)) {
      return ResponseEntity.notFound().build();
    }
    notes.put(id, new Note(user, text));
    return ResponseEntity.noContent().build();
  }

  /**
   * メモ。
   *
   * @param owner 持ち主の ID
   * @param text 本文
   */
  /* package */ record Note(String owner, String text) {}
}
