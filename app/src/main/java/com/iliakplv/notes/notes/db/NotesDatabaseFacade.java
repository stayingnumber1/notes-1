package com.iliakplv.notes.notes.db;

import android.util.Log;
import com.iliakplv.notes.BuildConfig;
import com.iliakplv.notes.NotesApplication;
import com.iliakplv.notes.notes.AbstractNote;
import com.iliakplv.notes.notes.Label;

import java.util.LinkedList;
import java.util.List;

/**
 * Author: Ilya Kopylov
 * Date: 19.09.2013
 */
public class NotesDatabaseFacade {

	private static final String LOG_TAG = NotesDatabaseFacade.class.getSimpleName();
	private static NotesDatabaseFacade instance = new NotesDatabaseFacade();

	private static final int INVALID_NOTE_ID = -1;

	private List<NotesDatabaseEntry> notesDatabaseEntries; // List<NotesDatabaseEntry<AbstractNote>>
	private volatile boolean entriesListActual = false;
	private volatile int entriesListSize = -1;

	private NotesDatabaseEntry<AbstractNote> lastFetchedEntry;
	private volatile int lastFetchedEntryId = 0;
	private volatile boolean lastFetchedEntryActual = false;

	private List<DatabaseChangeListener> databaseListeners;
	private List<NoteChangeListener> noteListeners;


	private NotesDatabaseFacade() {}

	public static NotesDatabaseFacade getInstance() {
		return instance;
	}


	// notes

	public NotesDatabaseEntry<AbstractNote> getNote(int id) {
		final boolean needToRefresh = lastFetchedEntryId != id || !lastFetchedEntryActual;
		if (BuildConfig.DEBUG) {
			Log.d(LOG_TAG, "Note entry fetching (id=" + id + "). Cached entry " +
					(needToRefresh ? "NOT " : "") + "actual");
		}
		if (needToRefresh) {
			lastFetchedEntry = (NotesDatabaseEntry<AbstractNote>) performDatabaseTransaction(TransactionType.GetNote, id);
			lastFetchedEntryId = id;
			lastFetchedEntryActual = true;
		}
		return lastFetchedEntry;
	}

	public List<NotesDatabaseEntry> getAllNotes() {
		if (BuildConfig.DEBUG) {
			Log.d(LOG_TAG, "Notes entries fetching. Cached entries list " +
					(entriesListActual ? "" : "NOT ") + "actual");
		}
		if (!entriesListActual) {
			notesDatabaseEntries =
					 (List<NotesDatabaseEntry>) performDatabaseTransaction(TransactionType.GetAllNotes, null);
			entriesListSize = notesDatabaseEntries.size();
			entriesListActual = true;
		}
		return notesDatabaseEntries;
	}

	public int getNotesCount() {
		if (entriesListSize < 0) {
			getAllNotes();
		}
		return entriesListSize;
	}

	public synchronized int insertNote(AbstractNote note) {
		return (Integer) performDatabaseTransaction(TransactionType.InsertNote, note);
	}

	public synchronized boolean updateNote(int id, AbstractNote note) {
		return (Boolean) performDatabaseTransaction(TransactionType.UpdateNote, id, note);
	}

	public synchronized boolean deleteNote(int id) {
		return (Boolean) performDatabaseTransaction(TransactionType.DeleteNote, id);
	}


	// labels

	public List<NotesDatabaseEntry<Label>> getAllLabels() {
		return (List<NotesDatabaseEntry<Label>>) performDatabaseTransaction(TransactionType.GetAllLabels);
	}

	public synchronized int insertLabel(Label label) {
		return (Integer) performDatabaseTransaction(TransactionType.InsertLabel, label);
	}

	public synchronized boolean updateLabel(int id, Label label) {
		return (Boolean) performDatabaseTransaction(TransactionType.UpdateLabel, id, label);
	}

	public synchronized boolean deleteLabel(int id) {
		return (Boolean) performDatabaseTransaction(TransactionType.DeleteLabel, id);
	}


	// notes_labels

	public List<NotesDatabaseEntry<Label>> getLabelsForNote(int noteId) {
		return (List<NotesDatabaseEntry<Label>>) performDatabaseTransaction(TransactionType.GetLabelsForNote, noteId);
	}

	public List<NotesDatabaseEntry<AbstractNote>> getNotesForLabel(int labelId) {
		return (List<NotesDatabaseEntry<AbstractNote>>) performDatabaseTransaction(TransactionType.GetNotesForLabel, labelId);
	}

	public synchronized int insertLabelToNote(int noteId, int labelId) {
		return (Integer) performDatabaseTransaction(TransactionType.InsertLabelToNote, noteId, labelId);
	}

	public synchronized boolean deleteLabelFromNote(int noteId, int labelId) {
		return (Boolean) performDatabaseTransaction(TransactionType.DeleteLabelFromNote, noteId, labelId);
	}


	private Object performDatabaseTransaction(TransactionType transactionType, Object... args) {
		final NotesDatabaseAdapter adapter = new NotesDatabaseAdapter();
		adapter.open();

		Object result;
		int noteId = INVALID_NOTE_ID;
		int labelId;

		switch (transactionType) {
			case GetNote:
				noteId = (Integer) args[0];
				result = adapter.getNote(noteId);
				break;
			case GetAllNotes:
				result = adapter.getAllNotes();
				break;
			case InsertNote:
				result = adapter.insertNote((AbstractNote) args[0]);
				incrementEntriesListSize();
				break;
			case UpdateNote:
				noteId = (Integer) args[0];
				result = adapter.updateNote(noteId, (AbstractNote) args[1]);
				break;
			case DeleteNote:
				noteId = (Integer) args[0];
				final List<NotesDatabaseEntry<Label>> labelsForNote = adapter.getLabelsForNote(noteId);
				for (NotesDatabaseEntry<Label> entry : labelsForNote) {
					adapter.deleteNoteLabel(noteId, entry.getId());
				}
				result = adapter.deleteNote(noteId);
				decrementEntriesListSize();
				break;

			case GetAllLabels:
				result = adapter.getAllLabels();
				break;
			case InsertLabel:
				result = adapter.insertLabel((Label) args[0]);
				break;
			case UpdateLabel:
				labelId = (Integer) args[0];
				result = adapter.updateLabel(labelId, (Label) args[1]);
				break;
			case DeleteLabel:
				labelId = (Integer) args[0];
				final List<NotesDatabaseEntry<AbstractNote>> notesForLabel = adapter.getNotesForLabel(labelId);
				for (NotesDatabaseEntry<AbstractNote> entry : notesForLabel) {
					adapter.deleteNoteLabel(entry.getId(), labelId);
				}
				result = adapter.deleteLabel(labelId);
				break;

			case GetLabelsForNote:
				noteId = (Integer) args[0];
				result = adapter.getLabelsForNote(noteId);
				break;
			case GetNotesForLabel:
				labelId = (Integer) args[0];
				result = adapter.getNotesForLabel(labelId);
				break;
			case InsertLabelToNote:
				noteId = (Integer) args[0];
				labelId = (Integer) args[1];
				result = adapter.insertNoteLabel(noteId, labelId);
				break;
			case DeleteLabelFromNote:
				noteId = (Integer) args[0];
				labelId = (Integer) args[1];
				result = adapter.deleteNoteLabel(noteId, labelId);
				break;

			default:
				throw new IllegalArgumentException("Wrong transaction type: " + transactionType.name());
		}
		adapter.close();
		onTransactionPerformed(transactionType, noteId);
		return result;
	}

	private int incrementEntriesListSize() {
		if (entriesListSize < 0) {
			entriesListSize = 0;
		}
		entriesListSize++;
		return entriesListSize;
	}

	private int decrementEntriesListSize() {
		entriesListSize--;
		if (entriesListSize < 0) {
			entriesListSize = 0;
		}
		return entriesListSize;
	}

	private void onTransactionPerformed(TransactionType transactionType, int changedNoteId) {
		if (BuildConfig.DEBUG) {
			Log.d(LOG_TAG, "Database transaction (" + transactionType.name() +") performed");
		}
		if (existingNoteModificationTransaction(transactionType)) {
			if (BuildConfig.DEBUG) {
				Log.d(LOG_TAG, "Changed note id=" + changedNoteId);
			}
			if (changedNoteId != INVALID_NOTE_ID) {
				lastFetchedEntryActual = lastFetchedEntryId != changedNoteId;
			}
			notifyNoteListeners(changedNoteId);
		}
		if (databaseModificationTransaction(transactionType)) {
			entriesListActual = false;
			notifyDatabaseListeners();
		}

	}

	// Listeners

	private void notifyDatabaseListeners() {
		if (databaseListeners != null) {
			NotesApplication.executeInBackground(new Runnable() {
				@Override
				public void run() {
					for (DatabaseChangeListener listener : databaseListeners) {
						listener.onDatabaseChanged();
					}
				}
			});
		}
	}

	private void notifyNoteListeners(final int changedNoteId) {
		if (noteListeners != null) {
			NotesApplication.executeInBackground(new Runnable() {
				@Override
				public void run() {
					for (NoteChangeListener listener : noteListeners) {
						if (changedNoteId == INVALID_NOTE_ID || listener.getNoteId() == changedNoteId) {
							listener.onNoteChanged();
						}
					}
				}
			});
		}
	}

	public boolean addDatabaseChangeListener(DatabaseChangeListener listener) {
		if (listener == null) {
			throw new NullPointerException();
		}
		if (databaseListeners == null) {
			databaseListeners = new LinkedList<DatabaseChangeListener>();
		}
		return databaseListeners.add(listener);
	}

	public boolean addNoteChangeListener(NoteChangeListener listener) {
		if (listener == null) {
			throw new NullPointerException();
		}
		if (noteListeners == null) {
			noteListeners = new LinkedList<NoteChangeListener>();
		}
		return noteListeners.add(listener);
	}

	public boolean removeDatabaseChangeListener(DatabaseChangeListener listener) {
		if (databaseListeners != null) {
			return databaseListeners.remove(listener);
		}
		return false;
	}

	public boolean removeNoteChangeListener(NoteChangeListener listener) {
		if (noteListeners != null) {
			return noteListeners.remove(listener);
		}
		return false;
	}


	// Other

	private static boolean databaseModificationTransaction(TransactionType transactionType) {
		switch (transactionType) {
			case InsertNote:
			case UpdateNote:
			case DeleteNote:

			case InsertLabel:
			case UpdateLabel:
			case DeleteLabel:

			case InsertLabelToNote:
			case DeleteLabelFromNote:
				return true;
		}
		return false;
	}

	private static boolean existingNoteModificationTransaction(TransactionType transactionType) {
		switch (transactionType) {
			case UpdateNote:
			case DeleteNote:

			case UpdateLabel:
			case DeleteLabel:

			case InsertLabelToNote:
			case DeleteLabelFromNote:
				return true;
		}
		return false;
	}


	/*********************************************
	 *
	 *            Inner classes
	 *
	 *********************************************/

	private static enum TransactionType {
		GetNote,
		GetAllNotes,
		InsertNote,
		UpdateNote,
		DeleteNote,

		GetAllLabels,
		InsertLabel,
		UpdateLabel,
		DeleteLabel,

		GetLabelsForNote,
		GetNotesForLabel,
		InsertLabelToNote,
		DeleteLabelFromNote
	}

	public interface DatabaseChangeListener {

		/**
		 * Callback for notes database changing
		 * Called after transaction that affects database entries (insert, update or delete) was performed
		 * Called from background thread. If you want to refresh UI in this method do it on UI thread!
		 */
		public void onDatabaseChanged();
	}

	public interface NoteChangeListener {

		/**
		 * Callback for existing note changing
		 * Called after changing note that this listener watching
		 * Called from background thread. If you want to refresh UI in this method do it on UI thread!
		 */
		public void onNoteChanged();

		/**
		 * @return id of note which this listener watching
		 */
		public int getNoteId();
	}

}
