import { useCallback, useEffect, useRef, useState } from 'react';

export interface ImageItem {
    zdbID: string;
    imageThumbnail: string;
}

interface ApiResponse {
    results: ImageItem[];
    total: number;
    returnedRecords: number;
}

interface PagedImageWindowResult {
    /** Images to display on the current display page */
    images: ImageItem[];
    /** Current display page (1-based) */
    displayPage: number;
    /** Set the display page */
    setDisplayPage: (page: number) => void;
    /** Total number of images (null while loading) */
    totalImages: number | null;
    /** Total display pages (null while loading) */
    totalPages: number | null;
    /** Whether a fetch is in progress */
    loading: boolean;
}

const DISPLAY_PAGE_SIZE = 10;
const API_PAGE_SIZE = 10; // figure groups per API request

/**
 * Hook that provides a fixed-size display window over a lazily-fetched stream of images.
 *
 * The backend paginates by figure group (variable image count per group).
 * This hook accumulates images from successive API pages into a flat cache
 * and serves fixed-size display pages (DISPLAY_PAGE_SIZE images each) from it.
 *
 * To swap this out later, replace with any hook that returns the same
 * PagedImageWindowResult interface.
 */
export default function usePagedImageWindow(baseUrl: string): PagedImageWindowResult {
    const [displayPage, setDisplayPage] = useState(1);
    const [loading, setLoading] = useState(false);
    // Total image count from the dedicated count endpoint (null until loaded)
    const [knownTotalImages, setKnownTotalImages] = useState<number | null>(null);

    // Accumulated flat image list from all fetched API pages
    const imageCache = useRef<ImageItem[]>([]);
    // Which API pages (1-based) have been fetched
    const fetchedApiPages = useRef<Set<number>>(new Set());
    // Total figure groups reported by the API (used to estimate total images)
    const apiTotalGroups = useRef<number>(0);
    // Whether we've fetched all API pages
    const allApiPagesFetched = useRef(false);
    // Track in-flight fetches to avoid duplicates
    const inFlightPages = useRef<Set<number>>(new Set());

    // Force re-render when cache updates
    const [cacheVersion, setCacheVersion] = useState(0);

    // Reset when the base URL changes (new search)
    useEffect(() => {
        imageCache.current = [];
        fetchedApiPages.current = new Set();
        apiTotalGroups.current = 0;
        allApiPagesFetched.current = false;
        inFlightPages.current = new Set();
        setDisplayPage(1);
        setCacheVersion(0);
        setKnownTotalImages(null);
    }, [baseUrl]);

    // Fetch the exact total image count asynchronously
    useEffect(() => {
        if (!baseUrl) { return; }
        const separator = baseUrl.indexOf('?') < 0 ? '?' : '&';
        const countUrl = `${baseUrl}${separator}countOnly=true`;
        fetch(countUrl)
            .then(r => r.ok ? r.json() : null)
            .then(data => {
                if (data && typeof data.total === 'number') {
                    setKnownTotalImages(data.total);
                }
            })
            .catch(() => {});
    }, [baseUrl]);

    const fetchApiPage = useCallback(async (apiPage: number): Promise<ImageItem[]> => {
        if (fetchedApiPages.current.has(apiPage) || inFlightPages.current.has(apiPage)) {
            return [];
        }
        inFlightPages.current.add(apiPage);
        setLoading(true);

        try {
            const separator = baseUrl.indexOf('?') < 0 ? '?' : '&';
            const url = `${baseUrl}${separator}page=${apiPage}&limit=${API_PAGE_SIZE}`;
            const response = await fetch(url);
            if (!response.ok) { return []; }
            const data: ApiResponse = await response.json();

            apiTotalGroups.current = data.total;
            fetchedApiPages.current.add(apiPage);

            if (data.results.length === 0) {
                allApiPagesFetched.current = true;
            }

            // Insert images at the correct position in the cache.
            // We need to figure out where this API page's images go.
            // Since API pages are ordered, we append if this is the next sequential page,
            // or we need to rebuild. For simplicity, we keep a sorted approach:
            // fetch pages in order and append.
            //
            // For sequential access (common case): images just append.
            // For random access (jump to page): we fetch all intermediate pages.
            return data.results;
        } catch {
            return [];
        } finally {
            inFlightPages.current.delete(apiPage);
            setLoading(false);
        }
    }, [baseUrl]);

    const fetchApiPagesSequentially = useCallback(async (upToApiPage: number) => {
        const newImages: ImageItem[] = [];
        for (let p = 1; p <= upToApiPage; p++) {
            if (!fetchedApiPages.current.has(p) && !inFlightPages.current.has(p)) {
                const images = await fetchApiPage(p);
                newImages.push(...images);
            }
        }
        if (newImages.length > 0) {
            imageCache.current = [...imageCache.current, ...newImages];
            setCacheVersion(v => v + 1);
        }
    }, [fetchApiPage]);

    // Ensure we have enough images cached for the current display page
    useEffect(() => {
        const imagesNeeded = displayPage * DISPLAY_PAGE_SIZE;
        const cachedCount = imageCache.current.length;

        if (cachedCount >= imagesNeeded || allApiPagesFetched.current) {
            return;
        }

        // Estimate how many API pages we need.
        // Average images per API page based on what we've fetched so far.
        const fetchedCount = fetchedApiPages.current.size;
        const avgImagesPerApiPage = fetchedCount > 0
            ? cachedCount / fetchedCount
            : DISPLAY_PAGE_SIZE; // initial estimate: assume 1 image per group

        const apiPagesNeeded = Math.ceil(imagesNeeded / Math.max(avgImagesPerApiPage, 1));
        // Fetch a few extra to account for variance
        const targetApiPage = Math.min(apiPagesNeeded + 2, apiTotalGroups.current || apiPagesNeeded + 2);

        fetchApiPagesSequentially(targetApiPage);
    }, [displayPage, cacheVersion, fetchApiPagesSequentially]);

    // Fetch page 1 on mount / URL change
    useEffect(() => {
        if (baseUrl) {
            fetchApiPagesSequentially(1);
        }
    }, [baseUrl, fetchApiPagesSequentially]);

    // Slice the display window from the cache
    const start = (displayPage - 1) * DISPLAY_PAGE_SIZE;
    const images = imageCache.current.slice(start, start + DISPLAY_PAGE_SIZE);

    const totalImages = knownTotalImages;
    const totalPages = totalImages !== null
        ? Math.max(1, Math.ceil(totalImages / DISPLAY_PAGE_SIZE))
        : null;

    return {
        images,
        displayPage,
        setDisplayPage,
        totalImages,
        totalPages,
        loading,
    };
}
