import React, {useCallback, useEffect, useRef, useState} from 'react';
import usePagedImageWindow, {ImageItem} from '../hooks/usePagedImageWindow';

const IMG_URL = '/imageLoadUp/';
const POPUP_URL = '/action/image/publication/image-popup/';
const IMG_PAGE_URL = '/';

const LOADING_HTML = '<div style="padding: 2em; text-align: center;"><i class="fas fa-spinner fa-spin fa-2x"></i><div style="margin-top: 0.5em;">Loading...</div></div>';

interface ImageThumbnailProps {
    image: ImageItem;
    onHover: (image: ImageItem) => void;
    onLeave: () => void;
}

const ImageThumbnail = ({image, onHover, onLeave}: ImageThumbnailProps) => {
    return (
        <span className='imagebox-image-wrapper' style={{display: 'inline-block'}}>
            <a
                href={IMG_PAGE_URL + image.zdbID}
                onMouseEnter={() => onHover(image)}
                onMouseLeave={onLeave}
            >
                <img
                    src={IMG_URL + image.imageThumbnail}
                    className='xpresimg_img'
                    alt={image.zdbID}
                />
            </a>
        </span>
    );
};

interface ExpressionImageGalleryProps {
    query: string;
}

const ExpressionImageGallery = ({query}: ExpressionImageGalleryProps) => {
    const baseUrl = `/action/expression/image-gallery?${query}`;
    const {images, displayPage, setDisplayPage, totalImages, totalPages, loading} = usePagedImageWindow(baseUrl);

    const [pageInput, setPageInput] = useState('1');
    const [popupHtml, setPopupHtml] = useState<string | null>(null);
    const [showPopup, setShowPopup] = useState(false);
    const popupCache = useRef<Record<string, string>>({});
    const activeImageId = useRef<string | null>(null);
    const hideTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);

    // Pre-fetch popup HTML for all images on the current display page
    useEffect(() => {
        images.forEach((image) => {
            if (!popupCache.current[image.zdbID]) {
                fetch(POPUP_URL + image.zdbID + '?imgpop_displayed_width=670')
                    .then(r => r.text())
                    .then(html => {
                        popupCache.current[image.zdbID] = html;
                        if (activeImageId.current === image.zdbID) {
                            setPopupHtml(html);
                        }
                    })
                    .catch(() => {});
            }
        });
    }, [images]);

    useEffect(() => {
        setPageInput(String(displayPage));
    }, [displayPage]);

    const handleImageHover = useCallback((image: ImageItem) => {
        if (hideTimeout.current) { clearTimeout(hideTimeout.current); }
        activeImageId.current = image.zdbID;
        setShowPopup(true);

        const cached = popupCache.current[image.zdbID];
        if (cached) {
            setPopupHtml(cached);
        } else {
            setPopupHtml(LOADING_HTML);
        }
    }, []);

    const handleImageLeave = useCallback(() => {
        hideTimeout.current = setTimeout(() => {
            activeImageId.current = null;
            setShowPopup(false);
            setPopupHtml(null);
        }, 500);
    }, []);

    const handlePopupEnter = useCallback(() => {
        if (hideTimeout.current) { clearTimeout(hideTimeout.current); }
    }, []);

    const handlePopupLeave = useCallback(() => {
        activeImageId.current = null;
        setShowPopup(false);
        setPopupHtml(null);
    }, []);

    const handlePageInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
        setPageInput(e.target.value);
    };

    const handlePageInputCommit = () => {
        let p = parseInt(pageInput, 10);
        if (isNaN(p) || p < 1) { p = 1; }
        if (totalPages !== null && p > totalPages) { p = totalPages; }
        setDisplayPage(p);
        setPageInput(String(p));
    };

    const handleKeyDown = (e: React.KeyboardEvent) => {
        if (e.key === 'Enter') {
            handlePageInputCommit();
        }
    };

    if (!loading && totalImages === 0) {
        return null;
    }

    const totalImagesDisplay = totalImages !== null
        ? <>{totalImages} images</>
        : <i className='fas fa-spinner fa-spin' />;

    const totalPagesDisplay = totalPages !== null
        ? totalPages
        : <i className='fas fa-spinner fa-spin' />;

    const canGoNext = totalPages === null || displayPage < totalPages;
    const showControls = totalPages === null || totalPages > 1;

    return (
        <div id='xpresimg_all' style={{position: 'relative'}}>
            <div id='xpresimg_control_box' style={{marginTop: '20px'}}>
                <span id='xpresimg_thumbs_title'>Figure Gallery ({totalImagesDisplay})</span>
                {showControls && (
                    <span id='xpresimg_controls'>
                        {displayPage > 1 ? (
                            <a href='#' onClick={(e) => { e.preventDefault(); setDisplayPage(displayPage - 1); }}>
                                <img src='/images/arrow_back.png' alt='Previous' />
                            </a>
                        ) : (
                            <img src='/images/arrow_back_disabled.png' alt='First page' title='This is the first set' />
                        )}
                        <input
                            type='text'
                            size={3}
                            value={pageInput}
                            onChange={handlePageInputChange}
                            onBlur={handlePageInputCommit}
                            onKeyDown={handleKeyDown}
                        />
                        <span> / {totalPagesDisplay} </span>
                        {canGoNext ? (
                            <a href='#' onClick={(e) => { e.preventDefault(); setDisplayPage(displayPage + 1); }}>
                                <img src='/images/arrow_next.png' alt='Next' />
                            </a>
                        ) : (
                            <img src='/images/arrow_next_disabled.png' alt='Last page' title='This is the last set' />
                        )}
                    </span>
                )}
            </div>
            <div id='xpresimg_box'>
                {loading && images.length === 0 && <span>Loading...</span>}
                {images.map((image) => (
                    <ImageThumbnail
                        key={image.zdbID}
                        image={image}
                        onHover={handleImageHover}
                        onLeave={handleImageLeave}
                    />
                ))}
            </div>
            {showPopup && popupHtml && (
                <div
                    className='imagebox-popup'
                    style={{display: 'block'}}
                    dangerouslySetInnerHTML={{__html: popupHtml}}
                    onMouseEnter={handlePopupEnter}
                    onMouseLeave={handlePopupLeave}
                />
            )}
        </div>
    );
};

export default ExpressionImageGallery;
