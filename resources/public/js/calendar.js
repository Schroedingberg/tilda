/**
 * Calendar drag selection - minimal JS for click-drag date range picking.
 * Everything else (nav, cancel, SSE updates) handled by Datastar.
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

  // Check if click was on a pending indicator
  const getIndicator = (e) => {
    return e.target.closest('.indicator.pending[data-request-id]');
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

  const submitRange = async (start, end) => {
    const [from, to] = [start, end].sort();
    clearSelection();
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
    // If clicking on own pending indicator, delete it instead of dragging
    const indicator = getIndicator(e);
    if (indicator && indicator.dataset.requestTenant === tenant) {
      deleteRequest(indicator.dataset.requestId);
      e.preventDefault();
      return;
    }
    
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
      submitRange(startDay.dataset.day, endDay.dataset.day);
    }
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
