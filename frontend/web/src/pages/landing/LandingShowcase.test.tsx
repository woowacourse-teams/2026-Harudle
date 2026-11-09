import { describe, expect, it, jest } from '@jest/globals';
import { fireEvent, render, screen } from '@testing-library/react';
import { useRef } from 'react';
import LandingShowcase from './LandingShowcase';

jest.mock(
  './LandingExampleDemo',
  () =>
    ({ example }: { example: { title: string } }) => <p>{example.title}</p>,
);
jest.mock('./assets/friends-birthday.png', () => 'friends.png');
jest.mock('./assets/work-different-pages.png', () => 'work.png');
jest.mock('./assets/school-presentation.png', () => 'school.png');
jest.mock('./assets/slack-toothpaste.png', () => 'toothpaste.png');
jest.mock('./assets/slack-favorite.png', () => 'favorite.png');
jest.mock('./assets/slack-song-quiz.png', () => 'song.png');
jest.mock('./assets/slack-chorok.png', () => 'chorok.png');
jest.mock('./assets/slack-iq.png', () => 'iq.png');
jest.mock('./assets/slack-ihyun.png', () => 'ihyun.png');

const Harness = () => {
  const pageRef = useRef<HTMLElement>(null);
  return (
    <main ref={pageRef}>
      <LandingShowcase pageRef={pageRef} />
    </main>
  );
};

describe('랜딩 예시 스크롤', () => {
  it('내릴 때 순서대로 바뀌고 올릴 때 반대로 돌아간다', () => {
    const { container } = render(<Harness />);
    const page = container.querySelector('main');
    const section = container.querySelector('section');
    const sticky = section?.firstElementChild;
    if (
      !(page instanceof HTMLElement) ||
      !(section instanceof HTMLElement) ||
      !(sticky instanceof HTMLElement)
    )
      throw new Error('예시 영역이 없습니다');
    Object.defineProperty(page, 'clientHeight', { value: 1000 });
    Object.defineProperty(section, 'offsetHeight', { value: 3000 });
    Object.defineProperty(sticky, 'offsetHeight', { value: 1000 });
    let sectionTop = 0;
    jest
      .spyOn(page, 'getBoundingClientRect')
      .mockReturnValue({ top: 0 } as DOMRect);
    jest
      .spyOn(section, 'getBoundingClientRect')
      .mockImplementation(() => ({ top: sectionTop }) as DOMRect);
    expect(screen.getByRole('button', { name: '친구와' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    sectionTop = -600;
    fireEvent.scroll(page);
    expect(screen.getByRole('button', { name: '동료와' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    sectionTop = -1600;
    fireEvent.scroll(page);
    expect(screen.getByRole('button', { name: '학교에서' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    sectionTop = -600;
    fireEvent.scroll(page);
    expect(screen.getByRole('button', { name: '동료와' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });
});
