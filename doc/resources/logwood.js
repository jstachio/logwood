// The JDK's search, navigation, and theme switcher remain native. No CDN is needed.
const guide = document.querySelector('body.module-index-page main .block');
if (guide && !document.getElementById('logwood-guide-navigation')) {
  const headings = guide.querySelectorAll('h2[id], h3[id]');
  if (headings.length) {
    const navigation = document.createElement('details');
    navigation.id = 'logwood-guide-navigation';
    navigation.open = window.matchMedia('(min-width: 60rem)').matches;
    const summary = document.createElement('summary');
    summary.textContent = 'User guide contents';
    navigation.append(summary);
    const list = document.createElement('ul');
    for (const heading of headings) {
      const item = document.createElement('li');
      const link = document.createElement('a');
      link.href = '#' + heading.id;
      link.textContent = heading.textContent;
      item.append(link);
      list.append(item);
    }
    navigation.append(list);
    guide.prepend(navigation);
  }
}
