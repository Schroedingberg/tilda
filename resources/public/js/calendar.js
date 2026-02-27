/**
 * Calendar drag selection - minimal JS for click-drag date range picking.
 * Everything else (nav, SSE updates) handled by Datastar.
 * 
 * Selection logic:
 * - If selection starts on a day with YOUR pending request → delete that request
 * - Otherwise → create new request for selected range
 */
(function() {
  const calendar = document.querySelector('[data-calendar]');
  if (!calendar) return;

  const tenant = calendar.dataset.tenant;
  let startDay = null;
  let isDragging = false;

  // Get day element from mouse/touch event
  const getDay = (e) => {
    const point = e.touches?.[0] ?? e;
    const el = document.elementFromPoint(point.clientX, point.clientY);
    return el?.closest('[data-day]');
  };

  // Find tenant's pending request indicator on a day
  const getOwnPendingRequest = (dayEl) => {
    return dayEl?.querySelector(`.indicator.pending[data-request-tenant="${tenant}"]`);
  };

  // Get all day elements between two dates (inclusive)
  const daysBetween = (a, b) => {
    const [start, end] = [a, b].sort();
    return [...calendar.querySelectorAll('[data-day]')].filter(el => {
      const d = el.dataset.day;
      return d >= start && d <= end;
    });
  };

  const clearSelection = () => {
    calendar.querySelectorAll('.selecting').forEach(el => el.classList.remove('selecting'));
  };

  const updateSelection = (endDay) => {
    clearSelection();
    if (startDay && endDay) {
      daysBetween(startDay.dataset.day, endDay.dataset.day)
        .forEach(el => el.classList.add('selecting'));
    }
  };

  const createRequest = async (start, end) => {
    const [from, to] = [start, end].sort();
    await fetch('/requests', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ 
        'start-date': from + 'T00:00:00Z',
        'end-date': to + 'T23:59:59Z',
        'tenant-name': tenant
      })
    });
  };

  const deleteRequest = async (requestId) => {
    await fetch('/requests/' + requestId, { method: 'DELETE' });
  };

  const onStart = (e) => {
    const day = getDay(e);
    if (day && !day.classList.contains('past')) {
      startDay = day;
      isDragging = true;
      updateSelection(day);
      e.preventDefault();
    }
  };

  const onMove = (e) => {
    if (!isDragging) return;
    const day = getDay(e);
    if (day) updateSelection(day);
  };

  const onEnd = (e) => {
    if (!isDragging) return;
    const endDay = getDay(e) ?? [...calendar.querySelectorAll('.selecting')].pop();
    
    if (startDay && endDay) {
      // Check if selection starts on a day with our pending request
      const ownRequest = getOwnPendingRequest(startDay);
      if (ownRequest) {
        // Delete mode: cancel the existing request
        deleteRequest(ownRequest.dataset.requestId);
      } else {
        // Create mode: new request for selected range
        createRequest(startDay.dataset.day, endDay.dataset.day);
      }
    }
    
    clearSelection();
    startDay = null;
    isDragging = false;
  };

  // Mouse events
  calendar.addEventListener('mousedown', onStart);
  calendar.addEventListener('mousemove', onMove);
  calendar.addEventListener('mouseup', onEnd);
  calendar.addEventListener('mouseleave', onEnd);

  // Touch events
  calendar.addEventListener('touchstart', onStart, { passive: false });
  calendar.addEventListener('touchmove', onMove, { passive: true });
  calendar.addEventListener('touchend', onEnd);
})();
