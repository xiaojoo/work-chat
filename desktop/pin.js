const im = document.getElementById('im')
window.pin.onData(url => { im.src = url })
document.getElementById('x').addEventListener('click', () => window.pin.close())
addEventListener('keydown', e => { if (e.key === 'Escape') window.pin.close() })
