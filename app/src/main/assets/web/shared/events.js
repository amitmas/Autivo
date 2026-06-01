/**
 * BYD Champ - Events Module
 * Calendar-based recording browser with video playback, pagination & thumbnails
 */

window.BYD = window.BYD || {};

BYD.events = {
    currentDate: new Date(),
    selectedDate: null,
    currentFilter: 'all',
    recordings: [],
    totalCount: 0,
    datesWithRecordings: new Map(),
    currentPage: 1,
    pageSize: 12,
    totalPages: 1,
    
    // Multi-select state
    selectMode: false,
    selectedFiles: new Set(),

    // In-flight recording state. Populated only when the user is deep-linked
    // (?file=…) from a fresh notification AND the referenced file isn't yet
    // in the recordings list (it's still <name>.mp4.tmp on disk). The
    // recordings page renders a pinned placeholder card while this is set,
    // and polls /api/recordings/inflight/<file> every 2s to detect when the
    // post-record window finishes and the rename completes.
    inflightFilename: null,
    inflightPollHandle: null,

    // v3 actor/severity/proximity filter state (item 6). Empty values = no filter.
    actorFilter: { class: '', severity: '', proximity: '' },

    // Last observed player.muted value, used by the volumechange handler in
    // playVideo() to debounce slider-drag events from actual mute-button
    // transitions. Initialized lazily on first playVideo so we can tell the
    // first listener invocation apart from a real transition.
    _lastMutedState: null,

    /**
     * Read the recording.audioEnabled setting from the backend on every
     * call. Used by playVideo() to decide whether to default the mute
     * state to unmuted on first-ever clip review when the user has audio
     * recording enabled (so they can actually hear the captured audio
     * without a second tap).
     *
     * Deliberately uncached: the setting is toggled from recording.html,
     * and a stale init-time cache would leave the events page defaulting
     * future clips to unmuted after the user disabled audio (and vice
     * versa). The fetch is a cheap loopback (~5ms) and only fires once
     * per clip the user opens, so always-fresh is the simpler and safer
     * default.
     */
    async getAudioRecordingEnabled() {
        try {
            const resp = await fetch('/api/settings/audio-recording');
            const data = await resp.json();
            return data && data.success ? !!data.enabled : false;
        } catch (e) {
            return false;
        }
    },

    async init() {
        const urlParams = new URLSearchParams(window.location.search);
        const filterParam = urlParams.get('filter');
        if (filterParam && ['all', 'sentry', 'normal', 'proximity'].includes(filterParam)) {
            this.currentFilter = filterParam;
            document.querySelectorAll('.filter-tab').forEach(tab => {
                tab.classList.toggle('active', tab.dataset.filter === filterParam);
            });
        }
        const fileParam = urlParams.get('file');

        // iOS Web Push can't render options.image, so the SW forwards the
        // signed snapshot URL as ?hero=<encoded URL>. Render it inline at
        // the top of the page so the user sees the same picture they would
        // have seen on Android's banner.
        const heroParam = urlParams.get('hero');
        if (heroParam) this.renderHeroBanner(heroParam);

        this.renderCalendar();
        this.updateRecordingsTitle();
        this.updateCalendarButton();
        await this.loadDatesWithRecordings();
        await this.loadStorageStats();
        await this.loadRecordings();

        // Deep-link from a notification.
        //   - If the recording is finalized (in this.recordings): open the player.
        //   - If it's still being written (.mp4.tmp on disk): show a pinned
        //     placeholder card and poll for finalization.
        //   - If it's neither finalized nor in flight (older notification, file
        //     was deleted, etc.): silently no-op so we don't yank the user
        //     around.
        if (fileParam) {
            const found = this.recordings.find(r => r.filename === fileParam);
            if (found) {
                this.playVideo(fileParam);
            } else {
                this.checkAndPollInflight(fileParam);
            }
        }
        
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                this.closeVideo();
                this.closeCalendar();
            }
        });
        
        document.getElementById('videoModal').addEventListener('click', (e) => {
            if (e.target.id === 'videoModal') this.closeVideo();
        });
        
        document.getElementById('calendarPopup').addEventListener('click', (e) => {
            if (e.target.id === 'calendarPopup') this.closeCalendar();
        });
        
        const calendarPopup = document.getElementById('calendarPopup');
        const videoModal = document.getElementById('videoModal');
        
        const observer = new MutationObserver(() => {
            const isOpen = calendarPopup.classList.contains('active') || videoModal.classList.contains('active');
            document.body.style.overflow = isOpen ? 'hidden' : '';
        });
        
        observer.observe(calendarPopup, { attributes: true });
        observer.observe(videoModal, { attributes: true });

        // Stop inflight polling when the page is unloaded or hidden — without
        // this, setInterval keeps firing in the background after the user
        // navigates away (BFCache visit, swipe-to-back PWA gesture, etc.).
        // visibilitychange covers tab-hidden too, since the platform may not
        // run setInterval reliably while hidden anyway.
        const stopOnExit = () => this.stopInflightPolling();
        window.addEventListener('pagehide', stopOnExit);
        window.addEventListener('beforeunload', stopOnExit);
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'hidden') stopOnExit();
        });
    },

    /**
     * Inserts a full-bleed hero banner at the top of the page using the
     * pre-signed snapshot URL forwarded from the notification. Used as the
     * iOS Web Push fallback path because Safari ignores options.image.
     * The URL already carries a single-purpose signed token, so no auth
     * header is required and the browser fetches it directly.
     */
    renderHeroBanner(heroUrl) {
        try {
            // Idempotent: replace any existing banner so re-entries don't stack.
            var existing = document.getElementById('eventsHeroBanner');
            if (existing && existing.parentNode) {
                existing.parentNode.removeChild(existing);
            }
            var img = document.createElement('img');
            img.id = 'eventsHeroBanner';
            img.src = heroUrl;
            img.alt = '';
            img.style.cssText =
                'display:block;width:100%;max-height:42vh;object-fit:cover;' +
                'border-radius:20px;margin:12px auto 16px;' +
                'box-shadow:0 8px 24px rgba(0,0,0,0.35);';
            // Fail silently if the token expired — text-only fallback is fine.
            img.onerror = function () {
                if (img.parentNode) img.parentNode.removeChild(img);
            };
            // Insert above the filter tabs so it dominates the first viewport.
            var anchor = document.querySelector('.filter-tab');
            var host = anchor && anchor.parentNode ? anchor.parentNode : document.body;
            host.parentNode.insertBefore(img, host);
        } catch (e) { /* best-effort */ }
    },

    /** Idempotent stop — safe to call multiple times. */
    stopInflightPolling() {
        if (this.inflightPollHandle) {
            clearInterval(this.inflightPollHandle);
            this.inflightPollHandle = null;
        }
    },
    
    toggleCalendar() {
        document.getElementById('calendarPopup').classList.toggle('active');
    },
    
    closeCalendar() {
        document.getElementById('calendarPopup').classList.remove('active');
    },
    
    updateCalendarButton() {
        const btn = document.getElementById('calendarToggle');
        const text = document.getElementById('calendarBtnText');
        
        if (this.selectedDate) {
            const date = new Date(this.selectedDate + 'T00:00:00');
            text.textContent = date.toLocaleDateString(BYD.i18n.getLang(), { month: 'short', day: 'numeric' });
            btn.classList.add('has-date');
        } else {
            text.textContent = BYD.i18n.t('events.select_date');
            btn.classList.remove('has-date');
        }
    },
    
    renderCalendar() {
        const grid = document.getElementById('calendarGrid');
        const title = document.getElementById('calendarTitle');
        const year = this.currentDate.getFullYear();
        const month = this.currentDate.getMonth();
        
        // i18n: Use Intl.DateTimeFormat for localized month/weekday names instead of hardcoded English arrays.
        var monthDate = new Date(year, month, 1);
        var monthName;
        try {
            monthName = new Intl.DateTimeFormat(BYD.i18n.getLang(), { month: 'long' }).format(monthDate);
        } catch (e) {
            monthName = monthDate.toLocaleDateString(BYD.i18n.getLang(), { month: 'long' });
        }
        title.textContent = monthName + ' ' + year;
        grid.innerHTML = '';

        var weekdayFmt;
        try {
            weekdayFmt = new Intl.DateTimeFormat(BYD.i18n.getLang(), { weekday: 'short' });
        } catch (e) { weekdayFmt = null; }
        // Use Sunday 2024-01-07 .. Saturday 2024-01-13 as a known week for label rendering
        for (var w = 0; w < 7; w++) {
            const dateForDay = new Date(2024, 0, 7 + w); // Sun..Sat
            const label = weekdayFmt ? weekdayFmt.format(dateForDay) : dateForDay.toLocaleDateString(BYD.i18n.getLang(), { weekday: 'short' });
            const el = document.createElement('div');
            el.className = 'calendar-weekday';
            el.textContent = label;
            grid.appendChild(el);
        }
        
        const firstDay = new Date(year, month, 1).getDay();
        const daysInMonth = new Date(year, month + 1, 0).getDate();
        const daysInPrevMonth = new Date(year, month, 0).getDate();
        const todayStr = this.formatDateKey(new Date());
        
        for (let i = firstDay - 1; i >= 0; i--) {
            const day = daysInPrevMonth - i;
            this.addDayCell(grid, day, this.formatDateKey(new Date(year, month - 1, day)), true);
        }
        
        for (let day = 1; day <= daysInMonth; day++) {
            const dateKey = this.formatDateKey(new Date(year, month, day));
            this.addDayCell(grid, day, dateKey, false, dateKey === todayStr, this.selectedDate === dateKey);
        }
        
        const totalCells = grid.children.length;
        for (let day = 1; totalCells + day - 7 <= 42; day++) {
            this.addDayCell(grid, day, this.formatDateKey(new Date(year, month + 1, day)), true);
        }
    },
    
    addDayCell(grid, day, dateKey, isOtherMonth, isToday, isSelected) {
        const el = document.createElement('div');
        el.className = 'calendar-day';
        el.textContent = day;
        el.dataset.date = dateKey;
        
        if (isOtherMonth) el.classList.add('other-month');
        if (isToday) el.classList.add('today');
        if (isSelected) el.classList.add('selected');
        
        // Disable future dates
        const today = new Date();
        today.setHours(0, 0, 0, 0);
        const cellDate = new Date(dateKey + 'T00:00:00');
        const isFuture = cellDate > today;
        
        if (isFuture) {
            el.classList.add('disabled');
        } else {
            const dateInfo = this.datesWithRecordings.get(dateKey);
            if (dateInfo) {
                el.classList.add('has-recordings');
                if (dateInfo.hasSentry) el.classList.add('has-sentry');
            }
            el.addEventListener('click', () => this.selectDate(dateKey));
        }
        
        grid.appendChild(el);
    },
    
    formatDateKey(date) {
        return date.getFullYear() + '-' + 
               String(date.getMonth() + 1).padStart(2, '0') + '-' + 
               String(date.getDate()).padStart(2, '0');
    },
    
    prevMonth() {
        this.currentDate.setMonth(this.currentDate.getMonth() - 1);
        this.renderCalendar();
    },
    
    nextMonth() {
        this.currentDate.setMonth(this.currentDate.getMonth() + 1);
        this.renderCalendar();
    },
    
    selectDate(dateKey) {
        this.selectedDate = this.selectedDate === dateKey ? null : dateKey;
        document.querySelectorAll('.calendar-day').forEach(el => {
            el.classList.toggle('selected', el.dataset.date === this.selectedDate);
        });
        this.updateRecordingsTitle();
        this.updateCalendarButton();
        this.currentPage = 1;
        this.loadRecordings();
        this.closeCalendar();
    },
    
    setFilter(filter) {
        this.currentFilter = filter;
        document.querySelectorAll('.filter-tab').forEach(tab => {
            tab.classList.toggle('active', tab.dataset.filter === filter);
        });
        this.updateRecordingsTitle();
        this.currentPage = 1;
        this.loadRecordings();
    },

    /**
     * Set v3 actor / severity / proximity / place filter (item 6 + place).
     * Empty value clears the row. Updates chip active states.
     *
     * Place IS server-side — sent as the {@code place} query param to
     * /api/recordings so pagination + totalCount stay honest under the
     * filter. The previous client-side approach hid matching clips on
     * later pages because the server returned only one page at a time.
     */
    setActorFilter(kind, value) {
        if (!this.actorFilter) {
            this.actorFilter = { class: '', severity: '', proximity: '', place: '' };
        }
        this.actorFilter[kind] = value || '';
        const rowSel = '.filter-tabs[data-filter-row="' + kind + '"] .filter-chip';
        document.querySelectorAll(rowSel).forEach(chip => {
            chip.classList.toggle('active', (chip.dataset[kind] || '') === (value || ''));
        });
        this.currentPage = 1;
        // All four filters round-trip to the server now. The chip-row
        // re-render piggybacks on the loadRecordings completion — see
        // loadRecordings() which calls loadPlaceChips() after the fetch.
        this.loadRecordings();
    },

    /**
     * Rebuild the dynamic Place chip row from the currently loaded
     * recordings. Called from loadRecordings() after each successful
     * fetch. Hidden when no clip in the loaded set carries a
     * place.short — legacy/feature-disabled users never see this row.
     *
     * Chip identity is the lowercase short label; the chip TEXT is the
     * canonical mixed-case form picked from the most recently captured
     * clip in each bucket. Top 8 by count, alpha-tiebreak.
     */
    /**
     * Fetch the chip set from /api/recordings/places, scoped by the
     * SAME filter context as the recordings list (type, date, class,
     * severity, proximity — minus the place filter itself, since
     * narrowing the chips by the active place would always return only
     * the active chip).
     *
     * <p>This replaces the previous client-side derivation from
     * `this.recordings` which was page-bounded — places that existed
     * only on later pages would never show up as chips. The server
     * now does the full scan + bucket + top-N once per filter change.
     */
    async loadPlaceChips() {
        const row = document.getElementById('placeFilterRow');
        if (!row) return;
        // In-flight token: a rapid succession of filter taps can dispatch
        // two parallel loadPlaceChips. Without this guard the later
        // request's response could lose the race to the earlier one and
        // memoize a stale result, producing a momentary chip-row
        // inconsistency. We keep a monotonic seq and only honour the
        // most recent response — older fetches finish but their state
        // updates are dropped.
        const seq = (this._placeChipsSeq = (this._placeChipsSeq || 0) + 1);
        try {
            const params = [];
            if (this.currentFilter !== 'all') params.push('type=' + this.currentFilter);
            if (this.selectedDate) params.push('date=' + this.selectedDate);
            if (this.actorFilter && this.actorFilter.class)     params.push('class=' + encodeURIComponent(this.actorFilter.class));
            if (this.actorFilter && this.actorFilter.severity)  params.push('severity=' + encodeURIComponent(this.actorFilter.severity));
            if (this.actorFilter && this.actorFilter.proximity) params.push('proximity=' + encodeURIComponent(this.actorFilter.proximity));
            const queryStr = params.join('&');
            // Fetch memo: paging through the same filter set (prev/next
            // page) reissues loadRecordings → loadPlaceChips with the
            // same query string. The chip set is identical because place
            // is excluded from the chip endpoint's params; replay the
            // last response instead of round-tripping. Invalidated by:
            //   - any class/severity/proximity/type/date filter change
            //     (changes queryStr)
            //   - delete/batch-delete (clears memo via _placeFetchMemo
            //     reset in the delete handlers below).
            // We deliberately memo the FETCH only — _renderPlaceChipsFromServer
            // still runs because the active place filter may differ
            // (its memo via _placeChipsSignature handles DOM-skip when
            // both filter + chip set are unchanged).
            if (this._placeFetchMemoQuery === queryStr && this._placeFetchMemoData) {
                // Memo replay is synchronous, so the seq guard is not
                // strictly required here, but check anyway for symmetry
                // with the fetch path.
                if (seq !== this._placeChipsSeq) return;
                this._renderPlaceChipsFromServer(row, this._placeFetchMemoData.places || []);
                return;
            }
            const url = '/api/recordings/places' + (queryStr ? '?' + queryStr : '');
            const res = await fetch(url);
            const data = await res.json();
            // Stale-response guard — drop everything if a newer fetch was
            // dispatched while we were awaiting. Do this BEFORE writing
            // the memo so a stale result can't shadow the fresh one.
            if (seq !== this._placeChipsSeq) return;
            if (!data || !data.success) {
                row.style.display = 'none';
                return;
            }
            this._placeFetchMemoQuery = queryStr;
            this._placeFetchMemoData = data;
            this._renderPlaceChipsFromServer(row, data.places || []);
        } catch (e) {
            console.warn('loadPlaceChips failed:', e);
            // Same stale-response guard for the failure path.
            if (seq !== this._placeChipsSeq) return;
            row.style.display = 'none';
        }
    },

    /**
     * Drop the cached chip-set fetch. Called from the delete/batch-delete
     * handlers so the chip row reflects "places where clips still exist"
     * after a destructive action. Without this, a user deleting the last
     * Cheras clip would still see a "Cheras" chip until they changed
     * another filter.
     */
    _invalidatePlaceMemo() {
        this._placeFetchMemoQuery = null;
        this._placeFetchMemoData = null;
        this._placeChipsSignature = '';
    },

    /**
     * Render the chip row from a {@code [{key, label, count}]} array
     * fetched from the server. Memoizes via a signature that includes
     * the active filter key so paginations within the same chip set
     * skip the DOM mutation entirely.
     */
    _renderPlaceChipsFromServer(row, places) {
        if (!places || places.length === 0) {
            row.style.display = 'none';
            // If the active filter is no longer in the server set,
            // clear it. This can happen if the user deletes every
            // matching clip while the filter is on.
            if (this.actorFilter && this.actorFilter.place) {
                this.actorFilter.place = '';
                // The current /api/recordings response WAS fetched
                // with a place filter that's now invalid; trigger a
                // refetch so the visible list stops showing nothing.
                this.loadRecordings();
            }
            this._placeChipsSignature = '';
            return;
        }
        row.style.display = '';

        const activePlaceLower = (this.actorFilter && this.actorFilter.place || '').toLowerCase();

        // Memo signature: order-stable join of chip keys + active key.
        // Pure paginations don't refetch chips (loadRecordings does, but
        // the same query params produce the same server output, so this
        // memo skips the DOM rebuild).
        const signature = activePlaceLower + '|' + places.map(p => p.key || '').join(',');
        if (this._placeChipsSignature === signature) return;
        this._placeChipsSignature = signature;

        // Replace data chips while keeping the static "Any" chip at index 0.
        const anyChip = row.querySelector('.filter-chip[data-place=""]');
        Array.from(row.querySelectorAll('.filter-chip')).forEach(c => {
            if (c !== anyChip) c.remove();
        });
        if (anyChip) anyChip.classList.toggle('active', !activePlaceLower);

        // Drop a place filter that's no longer in the server set.
        if (activePlaceLower && !places.some(p => (p.key || '') === activePlaceLower)) {
            this.actorFilter.place = '';
            // Re-fetch the recordings list — the place arg we sent on
            // loadRecordings is now a no-match. Without this, the user
            // sees an empty list with no UI clue what's blocking it.
            this.loadRecordings();
            return;
        }

        places.forEach(place => {
            const btn = document.createElement('button');
            const key = place.key || '';
            btn.className = 'filter-chip' + (key === activePlaceLower ? ' active' : '');
            btn.setAttribute('data-place', key);
            // textContent escapes everything — the label flows through
            // untrusted (Nominatim / user-edited SafeLocation strings).
            btn.textContent = place.label || key;
            btn.addEventListener('click', () => {
                BYD.events.setActorFilter('place', key === activePlaceLower ? '' : key);
            });
            row.appendChild(btn);
        });
    },
    
    updateRecordingsTitle() {
        const title = document.getElementById('recordingsTitle');
        let prefix = this.selectedDate
            ? new Date(this.selectedDate + 'T00:00:00').toLocaleDateString(BYD.i18n.getLang(), { month: 'short', day: 'numeric', year: 'numeric' })
            : BYD.i18n.t('events.all');

        var suffixKey;
        if (this.currentFilter === 'sentry') suffixKey = 'events.title_sentry';
        else if (this.currentFilter === 'proximity') suffixKey = 'events.title_proximity';
        else suffixKey = 'events.title_recordings';
        title.textContent = BYD.i18n.t(suffixKey, {prefix: prefix});
    },
    
    updatePagination() {
        const pagination = document.getElementById('pagination');
        const prevBtn = document.getElementById('prevPageBtn');
        const nextBtn = document.getElementById('nextPageBtn');
        const info = document.getElementById('paginationInfo');

        if (this.totalPages <= 1) {
            pagination.style.display = 'none';
            return;
        }

        pagination.style.display = 'flex';
        prevBtn.disabled = this.currentPage <= 1;
        nextBtn.disabled = this.currentPage >= this.totalPages;
        info.textContent = BYD.i18n.t('events.page_of', {page: this.currentPage, total: this.totalPages});
    },
    
    prevPage() {
        if (this.currentPage > 1) {
            this.currentPage--;
            this.loadRecordings();
            document.getElementById('recordingsList').scrollTop = 0;
        }
    },
    
    nextPage() {
        if (this.currentPage < this.totalPages) {
            this.currentPage++;
            this.loadRecordings();
            document.getElementById('recordingsList').scrollTop = 0;
        }
    },

    async loadDatesWithRecordings() {
        try {
            const res = await fetch('/api/recordings/dates');
            const data = await res.json();
            if (data.success && data.dates) {
                this.datesWithRecordings.clear();
                data.dates.forEach(d => {
                    this.datesWithRecordings.set(d.date, { count: d.count, hasSentry: d.hasSentry });
                });
                this.renderCalendar();
            }
        } catch (e) {
            console.error('Failed to load dates:', e);
        }
    },
    
    async loadStorageStats() {
        try {
            const res = await fetch('/api/recordings/stats');
            const data = await res.json();
            if (data.success) {
                document.getElementById('storageUsed').textContent = data.totalSizeFormatted;
                const usedPercent = data.totalSpace > 0 ? (data.totalSize / data.totalSpace) * 100 : 0;
                document.getElementById('storageFill').style.width = Math.min(usedPercent, 100) + '%';
                document.getElementById('normalCount').textContent = data.normalCount || 0;
                document.getElementById('sentryCount').textContent = data.sentryCount || 0;
                document.getElementById('proximityCount').textContent = data.proximityCount || 0;
            }
        } catch (e) {
            console.error('Failed to load storage stats:', e);
        }
    },
    
    /**
     * Single source of truth for filename → thumbnail DOM id. Both the inflight
     * placeholder and the recordings-list renderer use this so the
     * "highlight just-finalized card" lookup can't drift from the id used
     * when the card was rendered.
     */
    _thumbDomId(filename) {
        return 'thumb-' + filename.replace(/[^a-zA-Z0-9]/g, '_');
    },

    /**
     * Probe the backend for an in-flight .mp4.tmp matching the deep-linked
     * filename. If it's there, set state and start polling so the placeholder
     * card upgrades to a normal entry the moment the post-record window ends.
     */
    async checkAndPollInflight(filename) {
        try {
            const res = await fetch('/api/recordings/inflight/' + encodeURIComponent(filename));
            const data = await res.json();
            if (!data || !data.inflight) {
                // File isn't in flight — no placeholder, no polling. The
                // recording either finished and was paginated past the first
                // page, or it never existed.
                return;
            }
            this.inflightFilename = filename;
            this.renderRecordings();
            this.startInflightPolling();
        } catch (e) {
            console.warn('[events] inflight probe failed:', e);
        }
    },

    startInflightPolling() {
        this.stopInflightPolling();
        this.inflightPollHandle = setInterval(async () => {
            if (!this.inflightFilename) {
                this.stopInflightPolling();
                return;
            }
            try {
                const res = await fetch('/api/recordings/inflight/' +
                    encodeURIComponent(this.inflightFilename));
                const data = await res.json();
                if (!data.inflight) {
                    // Rename finished. Clear the placeholder, reload the list
                    // so the freshly-renamed file appears in its proper slot,
                    // and stop polling. We deliberately do NOT auto-open the
                    // video — the user came here from a notification a few
                    // seconds ago; surfacing the entry visibly is enough.
                    const finishedFile = this.inflightFilename;
                    this.inflightFilename = null;
                    this.stopInflightPolling();
                    await this.loadRecordings();
                    // If the file landed on this page, briefly highlight it.
                    const node = document.getElementById(this._thumbDomId(finishedFile));
                    const card = node ? node.closest('.recording-card') : null;
                    if (card) {
                        card.classList.add('recording-card-just-finalized');
                        setTimeout(() => {
                            card.classList.remove('recording-card-just-finalized');
                        }, 2000);
                    }
                }
            } catch (e) {
                // Network blip — keep polling, don't bail.
            }
        }, 2000);
    },

    /**
     * Build the pinned "Recording in progress" card markup. Reuses the
     * existing recording-card / recording-thumbnail / recording-info CSS so
     * it slots in visually with the rest of the list. Distinguished by the
     * .recording-card-inflight modifier (subtle pulse) and a Recording badge
     * sitting where the duration badge would normally be.
     *
     * Thumbnail loads from the same /thumb/ endpoint, which now serves a sync
     * frame from the .mp4.tmp file before post-record finalises (see
     * RecordingsApiHandler.findVideoFile, allowInFlightTmp).
     */
    renderInflightCard(filename) {
        const thumbId = this._thumbDomId(filename);
        const fname = filename.length > 28 ? filename.substring(0, 25) + '...' : filename;
        const thumbUrl = '/thumb/' + encodeURIComponent(filename);
        return '' +
            '<div class="recording-card recording-card-inflight" data-filename="' + filename + '">' +
                '<div class="recording-thumbnail" id="' + thumbId + '" data-thumb="' + thumbUrl + '">' +
                    '<div class="thumb-placeholder"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg></div>' +
                    '<span class="inflight-badge"><span class="inflight-dot"></span>' + BYD.i18n.t('events.recording_badge') + '</span>' +
                '</div>' +
                '<div class="recording-info">' +
                    '<div class="recording-name"><span class="recording-badge live">' + BYD.i18n.t('events.live_badge') + '</span>' + fname + '</div>' +
                    '<div class="recording-meta"><span>' + BYD.i18n.t('events.available_in_seconds') + '</span></div>' +
                '</div>' +
            '</div>';
    },

    async loadRecordings() {
        const list = document.getElementById('recordingsList');
        list.innerHTML = '<div class="loading"><div class="spinner"></div></div>';

        try {
            let url = '/api/recordings';
            const params = [];
            if (this.currentFilter !== 'all') params.push('type=' + this.currentFilter);
            if (this.selectedDate) params.push('date=' + this.selectedDate);
            // v3 actor filters (item 6) + place filter (item 7) — all
            // server-side now so pagination + totalCount stay honest
            // under any combination of narrowing chips.
            if (this.actorFilter && this.actorFilter.class)     params.push('class=' + encodeURIComponent(this.actorFilter.class));
            if (this.actorFilter && this.actorFilter.severity)  params.push('severity=' + encodeURIComponent(this.actorFilter.severity));
            if (this.actorFilter && this.actorFilter.proximity) params.push('proximity=' + encodeURIComponent(this.actorFilter.proximity));
            if (this.actorFilter && this.actorFilter.place)     params.push('place=' + encodeURIComponent(this.actorFilter.place));
            params.push('page=' + this.currentPage);
            params.push('pageSize=' + this.pageSize);
            url += '?' + params.join('&');

            const res = await fetch(url);
            const data = await res.json();

            if (data.success) {
                this.recordings = data.recordings || [];
                this.totalPages = data.totalPages || 1;
                this.totalCount = data.totalCount || this.recordings.length;
                this.renderRecordings();
                this.updatePagination();
                document.getElementById('recordingsCount').textContent =
                    BYD.i18n.plural('events.video_count', this.totalCount);
                // Refresh the chip row from the dedicated places endpoint
                // — scoped by the SAME filter context (minus place
                // itself) so the user sees "places reachable under the
                // current Sentry/Dashcam/date narrowing." Kicked off
                // AFTER the recordings render so the user-visible list
                // doesn't wait on this auxiliary fetch.
                this.loadPlaceChips();
            }
        } catch (e) {
            console.error('Failed to load recordings:', e);
            list.innerHTML = '<div class="empty-state"><svg class="empty-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg><div class="empty-title">' + BYD.i18n.t('events.empty_failed_title') + '</div><div class="empty-text">' + BYD.i18n.t('events.empty_failed_text') + '</div></div>';
        }
    },
    
    renderRecordings() {
        const list = document.getElementById('recordingsList');

        // Inflight placeholder. We only render it when there's NO active
        // narrowing filter — class/severity/proximity/place all hide it.
        // Reason: the placeholder represents a not-yet-finalized .mp4
        // whose sidecar hasn't been written, so its place/severity/etc
        // are unknowable. Showing it under an active filter would have
        // it vanish (without being replaced) when the .mp4 finalizes
        // and the filter excludes it. The filter-based bar lets the
        // user see the active recording in the unfiltered or type-only
        // views; stricter narrowing implies "show me clips matching X"
        // which the placeholder can't promise to deliver.
        const filterNarrowing = !!(this.actorFilter
                && (this.actorFilter.class || this.actorFilter.severity
                        || this.actorFilter.proximity || this.actorFilter.place));
        const inflightHtml = (this.inflightFilename && !filterNarrowing)
            ? this.renderInflightCard(this.inflightFilename)
            : '';

        // Server applies every filter (type, date, class, severity,
        // proximity, place). The list arrives ready to render.
        const visible = this.recordings;

        if (visible.length === 0) {
            if (inflightHtml) {
                list.innerHTML = inflightHtml;
            } else {
                list.innerHTML = '<div class="empty-state"><svg class="empty-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg><div class="empty-title">' + BYD.i18n.t('events.empty_none_title') + '</div><div class="empty-text">' + BYD.i18n.t('events.empty_none_text') + '</div></div>';
            }
            return;
        }

        list.innerHTML = inflightHtml + visible.map(rec => {
            const thumbId = this._thumbDomId(rec.filename);
            const badge = rec.type === 'sentry' ? BYD.i18n.t('events.badge_sentry') : rec.type === 'proximity' ? BYD.i18n.t('events.badge_proximity') : BYD.i18n.t('events.badge_normal');
            const fname = rec.filename.length > 28 ? rec.filename.substring(0, 25) + '...' : rec.filename;
            const isSelected = this.selectedFiles.has(rec.filename);
            
            // Checkbox for select mode
            const checkbox = this.selectMode 
                ? '<label class="select-checkbox-wrap" onclick="event.stopPropagation()"><input type="checkbox" class="select-checkbox" ' + (isSelected ? 'checked' : '') + ' onchange="BYD.events.toggleFileSelection(\'' + rec.filename + '\', event)"></label>'
                : '';
            
            // Card click handler depends on mode
            const cardClick = this.selectMode 
                ? 'BYD.events.toggleFileSelection(\'' + rec.filename + '\')'
                : 'BYD.events.playVideo(\'' + rec.filename + '\')';
            
            // v3 enrichment (item 6): use hero JPEG when present, sev badge, actor summary
            const sev = (rec.peakSeverity || '').toUpperCase();
            const sevClass = sev === 'CRITICAL' ? 'sev-critical' : sev === 'ALERT' ? 'sev-alert' : '';
            const sevLabel = sev === 'CRITICAL' ? BYD.i18n.t('events.severity_critical')
                : sev === 'ALERT' ? BYD.i18n.t('events.severity_alert')
                : sev === 'NOTICE' ? BYD.i18n.t('events.severity_notice')
                : sev;
            const sevBadge = sev ? '<span class="recording-badge sev-' + sev.toLowerCase() + '">' + sevLabel + '</span>' : '';
            const thumbUrl = rec.heroThumbnailUrl || rec.thumbnailUrl || '';
            const personCount  = rec.personCount  || rec.personSpans  || 0;
            const vehicleCount = rec.vehicleCount || rec.vehicleSpans || 0;
            const bikeCount    = rec.bikeCount    || rec.bikeSpans    || 0;
            const animalCount  = rec.animalCount  || 0;
            const proxLabel = (function(p) {
                switch ((p||'').toUpperCase()) {
                    case 'VERY_CLOSE': return BYD.i18n.t('events.prox_very_close');
                    case 'CLOSE': return BYD.i18n.t('events.prox_close');
                    case 'MID': return BYD.i18n.t('events.prox_mid');
                    case 'FAR': return BYD.i18n.t('events.prox_far');
                    default: return '';
                }
            })(rec.peakProximity);
            let actorPills = '';
            if (personCount > 0)  actorPills += '<span class="pill">👤 ' + personCount + '</span>';
            if (vehicleCount > 0) actorPills += '<span class="pill">🚗 ' + vehicleCount + '</span>';
            if (bikeCount > 0)    actorPills += '<span class="pill">🚲 ' + bikeCount + '</span>';
            if (animalCount > 0)  actorPills += '<span class="pill">🐾 ' + animalCount + '</span>';
            if (proxLabel)        actorPills += '<span class="pill prox-' + (rec.peakProximity || 'UNKNOWN') + '">' + proxLabel + '</span>';
            const actorRow = actorPills ? '<div class="actor-summary">' + actorPills + '</div>' : '';

            // v3 geo enrichment — server-side parser populates rec.place
            // (medium/short/displayName/source/countryCode). Hidden when
            // missing so legacy clips and clips with no GPS fix render
            // exactly as before. HTML-escape the place name because it
            // can contain user-edited SafeLocation labels and
            // OpenStreetMap-emitted strings; both flow through the
            // sidecar untrusted.
            let placeRow = '';
            if (rec.place && (rec.place.medium || rec.place.short)) {
                const placeText = rec.place.medium || rec.place.short || '';
                const escaped = String(placeText)
                    .replace(/&/g, '&amp;')
                    .replace(/</g, '&lt;')
                    .replace(/>/g, '&gt;')
                    .replace(/"/g, '&quot;');
                placeRow = '<div class="recording-place">📍 ' + escaped + '</div>';
            }

            return '<div class="recording-card' + (isSelected ? ' selected' : '') + (sevClass ? ' ' + sevClass : '') + '" data-filename="' + rec.filename + '" onclick="' + cardClick + '">' +
                checkbox +
                '<div class="recording-thumbnail" id="' + thumbId + '" data-thumb="' + thumbUrl + '">' +
                '<div class="thumb-placeholder"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg></div>' +
                '<div class="play-icon"><svg width="16" height="16" viewBox="0 0 24 24" fill="white"><polygon points="5 3 19 12 5 21 5 3"/></svg></div>' +
                (rec.duration ? '<span class="duration-badge">' + rec.duration + '</span>' : '') +
                '</div>' +
                '<div class="recording-info">' +
                '<div class="recording-name"><span class="recording-badge ' + rec.type + '">' + badge + '</span>' + sevBadge + fname + '</div>' +
                '<div class="recording-meta"><span>' + rec.dateFormatted + '</span><span>' + rec.timeFormatted + '</span><span>' + rec.sizeFormatted + '</span></div>' +
                placeRow +
                actorRow +
                '</div>' +
                (this.selectMode ? '' : 
                '<div class="recording-actions">' +
                '<button class="action-btn" onclick="event.stopPropagation(); BYD.events.downloadVideo(\'' + rec.filename + '\')" title="' + BYD.i18n.t('common.download') + '"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg></button>' +
                '<button class="action-btn delete" onclick="event.stopPropagation(); BYD.events.deleteRecording(\'' + rec.filename + '\')" title="' + BYD.i18n.t('common.delete') + '"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg></button>' +
                '</div>') +
                '</div>';
        }).join('');
        
        // Load thumbnails async after render
        this.loadThumbnailsAsync();
    },
    
    // Load thumbnails asynchronously without blocking
    loadThumbnailsAsync() {
        document.querySelectorAll('.recording-thumbnail[data-thumb]').forEach(container => {
            const thumbUrl = container.dataset.thumb;
            if (!thumbUrl) return;
            
            this.loadSingleThumbnail(container, thumbUrl, 0);
        });
    },
    
    // Load single thumbnail with retry on 202
    loadSingleThumbnail(container, url, retryCount) {
        if (retryCount > 8) {
            // After max retries, show "no preview" state
            const placeholder = container.querySelector('.thumb-placeholder');
            if (placeholder) {
                placeholder.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg><span>' + BYD.i18n.t('events.no_preview') + '</span>';
            }
            return;
        }
        
        fetch(url).then(res => {
            if (res.status === 200) {
                return res.blob();
            } else if (res.status === 202) {
                // Generating, retry with exponential backoff
                const delay = Math.min(1000 * Math.pow(1.5, retryCount), 5000);
                setTimeout(() => this.loadSingleThumbnail(container, url, retryCount + 1), delay);
                return null;
            }
            return null;
        }).then(blob => {
            if (blob && blob.size > 0) {
                const imgUrl = URL.createObjectURL(blob);
                const placeholder = container.querySelector('.thumb-placeholder');
                if (placeholder) {
                    const img = document.createElement('img');
                    img.src = imgUrl;
                    img.alt = BYD.i18n.t('events.thumbnail_alt');
                    img.onload = () => placeholder.remove();
                    img.onerror = () => {
                        URL.revokeObjectURL(imgUrl);
                        placeholder.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg><span>' + BYD.i18n.t('events.no_preview') + '</span>';
                    };
                    container.insertBefore(img, container.firstChild);
                }
            }
        }).catch(() => {
            // Network error - show fallback
            const placeholder = container.querySelector('.thumb-placeholder');
            if (placeholder) {
                placeholder.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg><span>' + BYD.i18n.t('events.no_preview') + '</span>';
            }
        });
    },
    
    onThumbError(el) {
        const container = el.parentElement;
        container.innerHTML = '<div class="thumb-placeholder"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg><span>' + BYD.i18n.t('events.no_preview') + '</span></div><div class="play-icon"><svg width="16" height="16" viewBox="0 0 24 24" fill="white"><polygon points="5 3 19 12 5 21 5 3"/></svg></div>';
    },
    
    playVideo(filename) {
        const rec = this.recordings.find(r => r.filename === filename);
        if (!rec) return;
        
        document.getElementById('videoTitle').textContent = rec.filename;
        document.getElementById('videoDate').innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="18" rx="2" ry="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/></svg> ' + rec.dateFormatted;
        document.getElementById('videoTime').innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg> ' + rec.timeFormatted;
        document.getElementById('videoSize').innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg> ' + rec.sizeFormatted;
        
        const downloadBtn = document.getElementById('downloadBtn');
        downloadBtn.href = rec.videoUrl;
        downloadBtn.download = rec.filename;
        
        const player = document.getElementById('videoPlayer');
        player.setAttribute('playsinline', '');
        player.setAttribute('webkit-playsinline', '');
        player.src = rec.videoUrl;
        document.getElementById('videoModal').classList.add('active');
        player.load();
        // Browser autoplay needs muted=true. Apply the user's persisted
        // mute preference once the video is actually playing — BEFORE
        // that point, mute=true is mandatory; after, we restore.
        const self = this;
        player.oncanplay = function() {
            player.play().catch(function() {});
            // Decide the mute default. The native <video controls> exposes
            // its own mute toggle, so we don't render a second one — but we
            // still set the initial mute state intelligently:
            //   1. Explicit user choice in localStorage wins (the browser
            //      persists the native mute control's last state too, but
            //      we keep our own key for backward compat with any
            //      clients that read it).
            //   2. If no explicit choice yet AND the user has audio
            //      recording enabled, default to UNMUTED — they want to
            //      hear what was captured.
            //   3. Otherwise default to muted (safer; matches first-run).
            try {
                const stored = localStorage.getItem('byd.events.unmuted');
                if (stored !== null) {
                    player.muted = stored !== '1';
                } else {
                    // Fetch the live audio-recording flag. Keep the player
                    // muted while in flight — autoplay is already gated on
                    // muted=true, and the round-trip is ~5ms loopback so
                    // the visual flash from muted→unmuted is imperceptible
                    // for the audio-on case.
                    player.muted = true;
                    self.getAudioRecordingEnabled().then(function(enabled) {
                        if (enabled) player.muted = false;
                    });
                }
            } catch (e) {
                // localStorage may be denied in some contexts; fall back
                // to muted (the safer default).
                player.muted = true;
            }
            player.oncanplay = null;
        };

        // Persist mute changes the user makes via the native <video
        // controls> mute toggle so the next clip honors it. Without this
        // listener every clip would re-evaluate the audio-recording
        // default and ignore the user's last gesture.
        //
        // Important: volumechange fires on slider drags too, not just on
        // mute-button transitions. If the user drops the slider to 0,
        // player.muted stays false but volume becomes 0 — we'd persist '1'
        // (unmuted), then the next clip would start unmuted with volume 0,
        // appearing silent and broken. Track the previous mute state on
        // BYD.events so the closure persists across slider drags within a
        // clip AND across reopened modals (each playVideo call rebinds the
        // listener but reads the same _lastMutedState). Only persist on an
        // actual mute-state transition.
        const self2 = this;
        player.onvolumechange = function() {
            if (player.muted !== self2._lastMutedState) {
                self2._lastMutedState = player.muted;
                try {
                    localStorage.setItem('byd.events.unmuted', player.muted ? '0' : '1');
                } catch (e) {
                    // Persistence failure is fine — current session still honors it.
                }
            }
        };

        // SOTA: Load event timeline for this recording
        this.loadTimeline(rec.filename, player);
    },
    
    closeVideo() {
        const player = document.getElementById('videoPlayer');
        player.pause();
        player.src = '';
        document.getElementById('videoModal').classList.remove('active');
        
        // SOTA: Clean up timeline
        this.destroyTimeline();
    },
    
    downloadVideo(filename) {
        const rec = this.recordings.find(r => r.filename === filename);
        if (!rec) return;
        const a = document.createElement('a');
        a.href = rec.videoUrl;
        a.download = rec.filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
    },
    
    async deleteRecording(filename) {
        if (!confirm(BYD.i18n.t('events.confirm_delete_one', {filename: filename}))) return;

        // If the modal player is currently showing this recording, pause it
        // and detach the source BEFORE the DELETE goes through. Otherwise
        // the in-flight Range request races with the file disappearing and
        // the user sees an "error: source not supported" toast.
        try {
            const player = document.getElementById('videoPlayer');
            if (player && player.src && player.src.endsWith('/video/' + filename)) {
                this.closeVideo();
            }
        } catch (_) {}

        try {
            const res = await fetch('/api/recordings/' + filename, { method: 'DELETE' });
            const data = await res.json();

            if (data.success) {
                // Drop the place-chip fetch memo so the row re-derives
                // from the post-delete set; otherwise a now-empty bucket
                // would still show as a chip until the next filter flip.
                this._invalidatePlaceMemo();
                await this.loadDatesWithRecordings();
                await this.loadStorageStats();
                await this.loadRecordings();
                if (BYD.core && BYD.core.showToast) {
                    BYD.core.showToast(BYD.i18n.t('events.toast_deleted'), 'success');
                }
            } else {
                alert(BYD.i18n.t('events.alert_delete_failed', {error: data.error || BYD.i18n.t('errors.generic')}));
            }
        } catch (e) {
            console.error('Delete failed:', e);
            alert(BYD.i18n.t('events.alert_delete_failed_generic'));
        }
    },
    
    // ========================================================================
    // Multi-Select & Batch Delete
    // ========================================================================
    
    toggleSelectMode() {
        this.selectMode = !this.selectMode;
        this.selectedFiles.clear();
        this.updateSelectUI();
        this.renderRecordings();
    },
    
    exitSelectMode() {
        this.selectMode = false;
        this.selectedFiles.clear();
        this.updateSelectUI();
        this.renderRecordings();
    },
    
    toggleFileSelection(filename, event) {
        if (event) event.stopPropagation();
        
        if (this.selectedFiles.has(filename)) {
            this.selectedFiles.delete(filename);
        } else {
            this.selectedFiles.add(filename);
        }
        this.updateSelectUI();
        this.updateCardSelection(filename);
    },
    
    selectAll() {
        this.recordings.forEach(rec => this.selectedFiles.add(rec.filename));
        this.updateSelectUI();
        this.renderRecordings();
    },
    
    deselectAll() {
        this.selectedFiles.clear();
        this.updateSelectUI();
        this.renderRecordings();
    },
    
    updateCardSelection(filename) {
        const card = document.querySelector('[data-filename="' + filename + '"]');
        if (card) {
            card.classList.toggle('selected', this.selectedFiles.has(filename));
            const checkbox = card.querySelector('.select-checkbox');
            if (checkbox) checkbox.checked = this.selectedFiles.has(filename);
        }
    },
    
    updateSelectUI() {
        const toolbar = document.getElementById('selectToolbar');
        const selectBtn = document.getElementById('selectModeBtn');
        const count = document.getElementById('selectedCount');
        
        if (this.selectMode) {
            if (toolbar) toolbar.style.display = 'flex';
            if (selectBtn) selectBtn.classList.add('active');
            if (count) count.textContent = BYD.i18n.t('events.n_selected', {n: this.selectedFiles.size});
        } else {
            if (toolbar) toolbar.style.display = 'none';
            if (selectBtn) selectBtn.classList.remove('active');
        }
    },
    
    async batchDelete() {
        const count = this.selectedFiles.size;
        if (count === 0) return;
        
        if (!confirm(BYD.i18n.plural('events.confirm_delete_n', count))) return;
        
        const filenames = Array.from(this.selectedFiles);
        
        try {
            const res = await fetch('/api/recordings/batch-delete', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ filenames: filenames })
            });
            const data = await res.json();
            
            if (data.success) {
                const msg = data.failed > 0
                    ? BYD.i18n.t('events.batch_deleted_with_failures', {deleted: data.deleted, failed: data.failed})
                    : BYD.i18n.t('events.batch_deleted', {deleted: data.deleted});
                if (BYD.core && BYD.core.showToast) {
                    BYD.core.showToast(msg, data.failed > 0 ? 'warning' : 'success');
                }
                // Same memo invalidation as single-delete — keeps the
                // chip row in sync after a multi-clip purge.
                this._invalidatePlaceMemo();
                this.exitSelectMode();
                await this.loadDatesWithRecordings();
                await this.loadStorageStats();
                await this.loadRecordings();
            } else {
                alert(BYD.i18n.t('events.batch_delete_failed', {error: data.error || BYD.i18n.t('errors.generic')}));
            }
        } catch (e) {
            console.error('Batch delete failed:', e);
            alert(BYD.i18n.t('events.batch_delete_failed_generic'));
        }
    },
    
    // ========================================================================
    // SOTA: Event Timeline Scrubber
    // ========================================================================
    
    _timelineRaf: null,
    _timelineEvents: null,
    
    /**
     * Load event timeline data and render markers.
     * Backward compatible: if no JSON sidecar exists, timeline stays hidden.
     */
    async loadTimeline(filename, videoEl) {
        const scrubber = document.getElementById('timelineScrubber');
        const legend = document.getElementById('timelineLegend');
        const track = document.getElementById('timelineTrack');
        const playhead = document.getElementById('timelinePlayhead');
        
        // Guard: if timeline HTML elements don't exist (old cached page), skip silently
        if (!scrubber || !track || !playhead) return;
        
        // Reset
        track.innerHTML = '';
        playhead.style.left = '0px';
        scrubber.style.display = 'none';
        if (legend) legend.style.display = 'none';
        this._timelineEvents = null;
        
        try {
            const res = await fetch('/api/events/' + filename);
            const data = await res.json();
            
            if (!data.events || data.events.length === 0) {
                // No events — backward compatible, just hide the scrubber
                return;
            }
            
            this._timelineEvents = data;
            
            // Wait for video metadata to get duration
            const renderMarkers = () => {
                const duration = videoEl.duration;
                if (!duration || duration <= 0) return;
                
                const durationMs = data.durationMs > 0 ? data.durationMs : duration * 1000;
                
                track.innerHTML = '';
                
                for (const ev of data.events) {
                    const marker = document.createElement('div');
                    marker.className = 'timeline-marker type-' + ev.type;
                    
                    const leftPct = (ev.start / durationMs) * 100;
                    const widthPct = Math.max(((ev.end - ev.start) / durationMs) * 100, 0.5);
                    
                    marker.style.left = leftPct + '%';
                    marker.style.width = widthPct + '%';
                    track.appendChild(marker);
                }
                
                scrubber.style.display = 'block';
                if (legend) legend.style.display = 'flex';
            };
            
            if (videoEl.readyState >= 1) {
                renderMarkers();
            } else {
                videoEl.addEventListener('loadedmetadata', renderMarkers, { once: true });
            }
            
            // Click-to-seek on the timeline
            scrubber.onclick = (e) => {
                const rect = scrubber.getBoundingClientRect();
                const pct = (e.clientX - rect.left) / rect.width;
                if (videoEl.duration) {
                    videoEl.currentTime = pct * videoEl.duration;
                }
            };
            
            // Playhead tracking at 10 FPS (matches surveillance engine rate)
            const updatePlayhead = () => {
                if (!videoEl.paused && videoEl.duration > 0) {
                    const pct = (videoEl.currentTime / videoEl.duration) * 100;
                    playhead.style.left = pct + '%';
                }
                this._timelineRaf = requestAnimationFrame(updatePlayhead);
            };
            this._timelineRaf = requestAnimationFrame(updatePlayhead);
            
        } catch (e) {
            // Fetch failed — backward compatible, just hide
            console.debug('No timeline data for', filename);
        }
    },
    
    /**
     * Clean up timeline resources.
     */
    destroyTimeline() {
        if (this._timelineRaf) {
            cancelAnimationFrame(this._timelineRaf);
            this._timelineRaf = null;
        }
        this._timelineEvents = null;
        
        const scrubber = document.getElementById('timelineScrubber');
        const legend = document.getElementById('timelineLegend');
        if (scrubber) scrubber.style.display = 'none';
        if (legend) legend.style.display = 'none';
    }
};