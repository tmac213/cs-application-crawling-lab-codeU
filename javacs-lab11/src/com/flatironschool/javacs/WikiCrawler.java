package com.flatironschool.javacs;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;

import redis.clients.jedis.Jedis;


public class WikiCrawler {
	// keeps track of where we started
	private final String source;
	
	// the index where the results go
	private JedisIndex index;
	
	// queue of URLs to be indexed
	private Queue<String> queue = new LinkedList<String>();
	
	// fetcher used to get pages from Wikipedia
	final static WikiFetcher wf = new WikiFetcher();

	/**
	 * Constructor.
	 * 
	 * @param source
	 * @param index
	 */
	public WikiCrawler(String source, JedisIndex index) {
		this.source = source;
		this.index = index;
		queue.offer(source);
	}

	/**
	 * Returns the number of URLs in the queue.
	 * 
	 * @return
	 */
	public int queueSize() {
		return queue.size();	
	}

	/**
	 * Gets a URL from the queue and indexes it.
	 * @param b 
	 * 
	 * @return Number of pages indexed.
	 * @throws IOException
	 */
	public String crawl(boolean testing) throws IOException {
		return testing ? crawlLocally() : crawlRemotely();
	}

    private String crawlLocally() throws IOException {
        String url = queue.poll();
        Elements paragraphs = wf.readWikipedia(url);
        index.indexPage(url, paragraphs);
        queueInternalLinks(paragraphs);
        return url;
    }

    private String crawlRemotely() throws IOException {
        String url = queue.poll();
        if (index.isIndexed(url)) {
            return null;
        } else {
            Elements paragraphs = wf.fetchWikipedia(url);
            index.indexPage(url, paragraphs);
            queueInternalLinks(paragraphs);
            return url;
        }
    }
	
	/**
	 * Parses paragraphs and adds internal links to the queue.
	 * 
	 * @param paragraphs
	 */
	// NOTE: absence of access level modifier means package-level
	void queueInternalLinks(Elements paragraphs) {
        for (Element paragraph : paragraphs) {
            for (Node node : new ParagraphIterable(paragraph)) {
                if (node instanceof Element && isValidLink((Element) node)) {
                    queue.offer(String.format("https://en.wikipedia.org%s", node.attr("href")));
                }
            }
        }
	}

    private boolean isValidLink(Element e) {
        if (!isLink(e)) {
            return false;
        }
        if (!isInternalLink(e)) {
            return false;
        }
        return true;
    }

    private boolean isLink(Element e) {
        return e.tagName().equals("a");
    }

    private boolean isInternalLink(Element e) {
        return e.attr("href").startsWith("/wiki/");
    }

    private static class ParagraphIterable implements Iterable<Node> {
        Node root;

        public ParagraphIterable(Node root) {
            this.root = root;
        }

        public Iterator<Node> iterator() {
            return new ParagraphIterator(root);
        }
    }

    private static class ParagraphIterator implements Iterator<Node> {
        Deque<Node> nodeStack = new ArrayDeque<>();

        ParagraphIterator(Node root) {
            nodeStack.push(root);
        }

        public boolean hasNext() {
            return !nodeStack.isEmpty();
        }

        public Node next() {
            Node ret = nodeStack.pop();
            nodeStack.addAll(ret.childNodes());
            return ret;
        }
    }

	public static void main(String[] args) throws IOException {
		
		// make a WikiCrawler
		Jedis jedis = JedisMaker.make();
		JedisIndex index = new JedisIndex(jedis); 
		String source = "https://en.wikipedia.org/wiki/Java_(programming_language)";
		WikiCrawler wc = new WikiCrawler(source, index);
		
		// for testing purposes, load up the queue
		Elements paragraphs = wf.fetchWikipedia(source);
		wc.queueInternalLinks(paragraphs);

		// loop until we index a new page
		String res;
		do {
			res = wc.crawl(false);

            // REMOVE THIS BREAK STATEMENT WHEN crawl() IS WORKING
            break;
		} while (res == null);
		
		Map<String, Integer> map = index.getCounts("the");
		for (Entry<String, Integer> entry: map.entrySet()) {
			System.out.println(entry);
		}
	}
}
